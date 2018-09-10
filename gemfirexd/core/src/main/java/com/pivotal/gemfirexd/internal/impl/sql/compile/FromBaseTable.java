/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable

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

package	com.pivotal.gemfirexd.internal.impl.sql.compile;

// GemStone changes BEGIN
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.internal.catalog.IndexDescriptor;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeap;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoContext;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.TableQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ClassName;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.classfile.VMOpcode;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.MethodBuilder;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableArrayHolder;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableIntHolder;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.LanguageProperties;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.AccessPath;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CostEstimate;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.JoinStrategy;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Optimizable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.OptimizablePredicate;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.OptimizablePredicateList;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Optimizer;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.RequiredRowOrdering;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.RowOrdering;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ViewDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.StoreCostController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.util.JBitSet;
import com.pivotal.gemfirexd.internal.iapi.util.ReuseFactory;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ActivationClassBuilder;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ExpressionClassBuilder;
import com.pivotal.gemfirexd.internal.impl.sql.compile.OrListNode.ElementSpecification;
// GemStone changes END



import java.util.ArrayDeque;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import java.util.HashSet;
import java.util.Set;

/**
 * A FromBaseTable represents a table in the FROM list of a DML statement,
 * as distinguished from a FromSubquery, which represents a subquery in the
 * FROM list. A FromBaseTable may actually represent a view.  During parsing,
 * we can't distinguish views from base tables. During binding, when we
 * find FromBaseTables that represent views, we replace them with FromSubqueries.
 * By the time we get to code generation, all FromSubqueries have been eliminated,
 * and all FromBaseTables will represent only true base tables.
 * <p>
 * <B>Positioned Update</B>: Currently, all columns of an updatable cursor
 * are selected to deal with a positioned update.  This is because we don't
 * know what columns will ultimately be needed from the UpdateNode above
 * us.  For example, consider:<pre><i>
 *
 * 	get c as 'select cint from t for update of ctinyint'
 *  update t set ctinyint = csmallint
 *
 * </pre></i> Ideally, the cursor only selects cint.  Then,
 * something akin to an IndexRowToBaseRow is generated to
 * take the CursorResultSet and get the appropriate columns
 * out of the base table from the RowLocation retunrned by the
 * cursor.  Then the update node can generate the appropriate
 * NormalizeResultSet (or whatever else it might need) to
 * get things into the correct format for the UpdateResultSet.
 * See CurrentOfNode for more information.
 *
 */

public class FromBaseTable extends FromTable
//GemStone changes BEGIN
implements Cloneable
//GemStone changes END
{
	static final int UNSET = -1;

	TableName		tableName;
	TableDescriptor	tableDescriptor;

	ConglomerateDescriptor		baseConglomerateDescriptor;
	ConglomerateDescriptor[]	conglomDescs;

	int				updateOrDelete;
	
	/*
	** The number of rows to bulkFetch.
	** Initially it is unset.  If the user
	** uses the bulkFetch table property,	
	** it is set to that.  Otherwise, it
	** may be turned on if it isn't an updatable
	** cursor and it is the right type of
	** result set (more than 1 row expected to
	** be returned, and not hash, which does its
	** own bulk fetch, and subquery).
	*/
	int 			bulkFetch = UNSET;

	private double	singleScanRowCount;

	private FormatableBitSet referencedCols;
	private ResultColumnList templateColumns;

	/* A 0-based array of column names for this table used
	 * for optimizer trace.
	 */
	private String[] columnNames;

	private String raParentResultSetId;
	private long fkIndexConglomId;	
	private int[] fkColArray;

	/**
	 * Restriction as a PredicateList
	 */
	PredicateList baseTableRestrictionList;
	PredicateList nonBaseTableRestrictionList;
	PredicateList restrictionList;
// GemStone changes BEGIN
	public PredicateList storeRestrictionList;
	public PredicateList nonStoreRestrictionList;
	private SelectQueryInfo qInfo;

	// the GROUP BY columns for this table, if any
	private GroupByList gbl;

	public static final int ONE_ROW_NONE = 0;
	public static final int ONE_ROW_UNIQUE = 1;
	public static final int ONE_ROW_NONUNIQUE = 2;
	
	private int flags =0x00000000;
	private  static final byte createGFEResultSet = 0x01;
        private static final  byte specialRegionSize = 0x02;
        private static final  byte  skipIndexRowToBaseRow = 0x04;
        // true if we are to do a special scan to retrieve the last value
        // in the index
        private static final byte specialMaxScan = 0x08;

        // true if we are to do a distinct scan
        private static final byte distinctScan = 0x10;
        

        /**
         *Information for dependent table scan for Referential Actions
         */
        private static final byte raDependentScan = 0x20;
        

        /* We may turn off bulk fetch for a variety of reasons,
         * including because of the min optimization.  
         * bulkFetchTurnedOff is set to true in those cases.
         */
        private static final byte                 bulkFetchTurnedOff = 0x40;
        
        /* Whether or not we are going to do execution time "multi-probing"
         * on the table scan for this FromBaseTable.
         */
        private static final short                 multiProbing = 0x80;
        /* Variables for EXISTS FBTs */
        private static final short existsBaseTable = 0x0100;
        private static final short isNotExists = 0x0200;  //is a NOT EXISTS base table
        private static final short getUpdateLocks = 0x0400;
        private static final short  delayScanOpening = 0x0800;
        private static final short  optimizeForOffHeap = 0x1000;
        private static final short  indexAccesesBaseTable = 0x2000;
// GemStone changes END

	PredicateList requalificationRestrictionList;

	public static final int UPDATE = 1;
	public static final int DELETE = 2;
  
	
	private JBitSet dependencyMap;

	// GemStone Changes Begin
        @Override
        public FromBaseTable clone() {
          return new FromBaseTable(this);
        }
        
        /** 
         * Default Constructor
         */
        public FromBaseTable() {
          super();
        }
        
        /**
         * Copy Constructor
         * 
         * For use in Non Collocated Join
         * Copy everything used till preprocess phase (before optimize phase)
         */
        public FromBaseTable(FromBaseTable other) {
          super(other);
          try {
            this.tableName = other.tableName;
            setOrigTableName(this.tableName);
            this.tableDescriptor = other.tableDescriptor;
            this.baseConglomerateDescriptor = other.baseConglomerateDescriptor;            
            this.updateOrDelete = other.updateOrDelete;
            this.templateColumns = this.resultColumns;
            if (this.resultColumns != null) {
              this.columnNames = this.resultColumns.getColumnNames();
            }
            if (other.referencedCols != null) {
              this.referencedCols = other.referencedCols.clone();
            }
            if (other.baseTableRestrictionList != null) {
              this.baseTableRestrictionList = new PredicateList();
              other.baseTableRestrictionList.copyPredicatesToOtherList(this.baseTableRestrictionList);
            }
            if (other.nonBaseTableRestrictionList != null) {
              this.nonBaseTableRestrictionList = new PredicateList();
              other.nonBaseTableRestrictionList.copyPredicatesToOtherList(this.nonBaseTableRestrictionList);
            }
            if (other.restrictionList != null) {
              this.restrictionList = new PredicateList();
              other.restrictionList.copyPredicatesToOtherList(this.restrictionList);
            }
            if (other.storeRestrictionList != null) {
              this.storeRestrictionList = new PredicateList();
              other.storeRestrictionList.copyPredicatesToOtherList(this.storeRestrictionList);
            }
            if (other.nonStoreRestrictionList != null) {
              this.nonStoreRestrictionList = new PredicateList();
              other.nonStoreRestrictionList.copyPredicatesToOtherList(this.nonStoreRestrictionList);
            }
            if (other.requalificationRestrictionList != null) {
              this.requalificationRestrictionList = new PredicateList();
              other.requalificationRestrictionList.copyPredicatesToOtherList(this.requalificationRestrictionList);
            }
            if (other.dependencyMap != null) {
              this.dependencyMap = (JBitSet)other.dependencyMap.clone();
            }
            this.bulkFetch = other.bulkFetch;
            this.gbl = other.gbl;
            this.flags = other.flags;
          } catch (StandardException e) {
            SanityManager.THROWASSERT(e);
          }
        }

        /**
         * Only used for NonCollocatedJoin
         * 
         * @throws StandardException
         */
        void modifyResultColumns(PredicateList predList, int semiJoinColNum)
            throws StandardException {
          ColumnReference chosenCol = null;
          if (semiJoinColNum != -1) {
            for (QueryTreeNode pred : predList) {
              Predicate joinP = (Predicate)pred;
              ColumnReference cr = joinP.getRelop().getColumnOperand(this);
              if (cr.getColumnNumber() == semiJoinColNum) {
                chosenCol = cr;
              }
            }
          }
          else {
            SanityManager.THROWASSERT("NCJ: Join must have one PK column");
          }
      
          boolean modified = false;
          if (chosenCol != null) {
            int relSize = this.resultColumns.size();
            for (int i = relSize - 1; i >= 0; i--) {
              ResultColumn rc = (ResultColumn)this.resultColumns.elementAt(i);
              if (rc.getColumnPosition() != chosenCol.getColumnNumber()) {
                this.resultColumns.remove(i);
                modified = true;
              }
            }
          }
          else {
            SanityManager
                .THROWASSERT("NCJ: One column must be selected for semi join");
          }
      
          SanityManager.ASSERT(this.resultColumns.size() == 1,
              "NCJ: One and Only one column corresponding to join key should remain");
      
          if (this.templateColumns != null && modified) {
            this.templateColumns = this.resultColumns;
          }
        }
        
        /**
         * Only used for Non Collocated Join
         * Set the referencedTableMap in FromBaseTable
         */
        public void setReferencedTableMap(int numTables)
        {
          this.referencedTableMap = new JBitSet(numTables);
          this.referencedTableMap.set(this.tableNumber);
        }
        // GemStone Changes End
	

	/**
	 * Initializer for a table in a FROM list. Parameters are as follows:
	 *
	 * <ul>
	 * <li>tableName			The name of the table</li>
	 * <li>correlationName	The correlation name</li>
	 * <li>derivedRCL		The derived column list</li>
	 * <li>tableProperties	The Properties list associated with the table.</li>
	 * </ul>
	 *
	 * <p>
	 *  - OR -
	 * </p>
	 *
	 * <ul>
	 * <li>tableName			The name of the table</li>
	 * <li>correlationName	The correlation name</li>
	 * <li>updateOrDelete	Table is being updated/deleted from. </li>
	 * <li>derivedRCL		The derived column list</li>
	 * </ul>
	 */
	@Override
  public void init(
							Object arg1,
							Object arg2,
				  			Object arg3,
							Object arg4)
	{
		if (arg3 instanceof Integer)
		{
			init(arg2, null);
			this.tableName = (TableName) arg1;
			this.updateOrDelete = ((Integer) arg3).intValue();
			resultColumns = (ResultColumnList) arg4;
		}
		else
		{
			init(arg2, arg4);
			this.tableName = (TableName) arg1;
			resultColumns = (ResultColumnList) arg3;
		}

		setOrigTableName(this.tableName);
		templateColumns = resultColumns;
		//default
		this.optimizeForOffHeap(true);
	}

	/**
	 * no LOJ reordering for base table.
	 */
	@Override
  public boolean LOJ_reorderable(int numTables)
				throws StandardException
	{
		return false;
	}

	@Override
  public JBitSet LOJgetReferencedTables(int numTables)
				throws StandardException
	{
		JBitSet map = new JBitSet(numTables);
		fillInReferencedTableMap(map);
		return map;
	}

	/*
	 * Optimizable interface.
	 */

// GemStone changes BEGIN
        @Override
        public void printSubNodes(int depth)
        {
                if (SanityManager.DEBUG)
                {
                        super.printSubNodes(depth);

                        printLabel(depth, "baseTableRestrictionList: \n");
                        printPredicateList(this.baseTableRestrictionList, depth);
                        printLabel(depth, "nonBaseTableRestrictionList: \n");
                        printPredicateList(this.nonBaseTableRestrictionList, depth);
                        printLabel(depth, "storeRestrictionList: \n");
                        printPredicateList(this.storeRestrictionList, depth);
                        printLabel(depth, "nonStoreRestrictionList: \n");
                        printPredicateList(this.nonStoreRestrictionList, depth);
                        printLabel(depth, "restrictionList: \n");
                        printPredicateList(this.restrictionList, depth);
                        printLabel(depth, "requalificationRestrictionList: \n");
                        printPredicateList(this.requalificationRestrictionList, depth);
                }
        }

        private void printPredicateList(PredicateList p, int depth) {
          if (p != null) {
            p.treePrint(depth);
          }
        }
// GemStone changes END

        /**
	 * @see Optimizable#nextAccessPath
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean nextAccessPath(Optimizer optimizer,
									OptimizablePredicateList predList,
									RowOrdering rowOrdering)
					throws StandardException
	{
		String userSpecifiedIndexName = getUserSpecifiedIndexName();
		AccessPath ap = getCurrentAccessPath();
		ConglomerateDescriptor currentConglomerateDescriptor =
												ap.getConglomerateDescriptor();

		optimizer.trace(Optimizer.CALLING_NEXT_ACCESS_PATH,
					   ((predList == null) ? 0 : predList.size()),
					   0, 0.0, getExposedName());

		/*
		** Remove the ordering of the current conglomerate descriptor,
		** if any.
		*/
		rowOrdering.removeOptimizable(getTableNumber());

		// RESOLVE: This will have to be modified to step through the
		// join strategies as well as the conglomerates.

		if (userSpecifiedIndexName != null)
		{
			/*
			** User specified an index name, so we should look at only one
			** index.  If there is a current conglomerate descriptor, and there
			** are no more join strategies, we've already looked at the index,
			** so go back to null.
			*/
			if (currentConglomerateDescriptor != null)
			{
				if ( ! super.nextAccessPath(optimizer,
											predList,
											rowOrdering) )
				{
					currentConglomerateDescriptor = null;
				}
			}
			else
			{
				optimizer.trace(Optimizer.LOOKING_FOR_SPECIFIED_INDEX,
								tableNumber, 0, 0.0, userSpecifiedIndexName);

				if (StringUtil.SQLToUpperCase(userSpecifiedIndexName).equals("NULL"))
				{
					/* Special case - user-specified table scan */
					currentConglomerateDescriptor =
						tableDescriptor.getConglomerateDescriptor(
										tableDescriptor.getHeapConglomerateId()
									);
				}
				else
				{
					/* User-specified index name */
					getConglomDescs();
				
					for (int index = 0; index < conglomDescs.length; index++)
					{
						currentConglomerateDescriptor = conglomDescs[index];
						String conglomerateName =
							currentConglomerateDescriptor.getConglomerateName();
						if (conglomerateName != null)
						{
							/* Have we found the desired index? */
							if (conglomerateName.equals(userSpecifiedIndexName))
							{
								break;
							}
						}
					}

					/* We should always find a match */
					if (SanityManager.DEBUG)
					{
						if (currentConglomerateDescriptor == null)
						{
							SanityManager.THROWASSERT(
								"Expected to find match for forced index " +
								userSpecifiedIndexName);
						}
					}
				}

				if ( ! super.nextAccessPath(optimizer,
											predList,
											rowOrdering))
				{
					if (SanityManager.DEBUG)
					{
						SanityManager.THROWASSERT("No join strategy found");
					}
				}
			}
		}
		else
		{
			if (currentConglomerateDescriptor != null)
			{
				/* 
				** Once we have a conglomerate descriptor, cycle through
				** the join strategies (done in parent).
				*/
				if ( ! super.nextAccessPath(optimizer,
											predList,
											rowOrdering))
				{
					/*
					** When we're out of join strategies, go to the next
					** conglomerate descriptor.
					*/
					currentConglomerateDescriptor = getNextConglom(currentConglomerateDescriptor);

					/*
					** New conglomerate, so step through join strategies
					** again.
					*/
					resetJoinStrategies(optimizer);

					if ( ! super.nextAccessPath(optimizer,
												predList,
												rowOrdering))
					{
						if (SanityManager.DEBUG)
						{
							SanityManager.THROWASSERT("No join strategy found");
						}
					}
				}
			}
			else
			{
				/* Get the first conglomerate descriptor */
				currentConglomerateDescriptor = getFirstConglom();

				if ( ! super.nextAccessPath(optimizer,
											predList,
											rowOrdering))
				{
					if (SanityManager.DEBUG)
					{
						SanityManager.THROWASSERT("No join strategy found");
					}
				}
			}
		}

		if (currentConglomerateDescriptor == null)
		{
			optimizer.trace(Optimizer.NO_MORE_CONGLOMERATES, tableNumber, 0, 0.0, null);
		}
		else
		{
			currentConglomerateDescriptor.setColumnNames(columnNames);
			optimizer.trace(Optimizer.CONSIDERING_CONGLOMERATE, tableNumber, 0, 0.0, 
							currentConglomerateDescriptor);
		}

// GemStone changes BEGIN
		if (predList != null && predList.hasOrList()) {
		  // tell the rowOrdering that ordering is not possible
		  // TODO: PERF: allow for rowOrdering with OR list if all
		  // the elements refer to the same index by merging the
		  // results in MultiColumnTableScanRS
		  optimizer.trace(Optimizer.ADDING_UNORDERED_OPTIMIZABLE,
		      ((predList == null) ? 0 : predList.size()),
		      0, 0.0, null);
		  rowOrdering.addUnorderedOptimizable(this);
		}
		else
// GemStone changes END
		/*
		** Tell the rowOrdering that what the ordering of this conglomerate is
		*/
		if (currentConglomerateDescriptor != null)
		{
// GemStone changes BEGIN
			// Hash1Indexes are unordered like the heap
			IndexRowGenerator irg;
			if (!currentConglomerateDescriptor.isIndex()
			    || GfxdConstants.LOCAL_HASH1_INDEX_TYPE.equals(
			        (irg = currentConglomerateDescriptor
			        .getIndexDescriptor()).indexType()))
			/* (original code)
			if ( ! currentConglomerateDescriptor.isIndex())
			*/
// GemStone changes END
			{
				/* If we are scanning the heap, but there
				 * is a full match on a unique key, then
				 * we can say that the table IS NOT unordered.
				 * (We can't currently say what the ordering is
				 * though.)
				 */
				if (! isOneRowResultSet(predList))
				{
					optimizer.trace(Optimizer.ADDING_UNORDERED_OPTIMIZABLE,
									 ((predList == null) ? 0 : predList.size()), 
									 0, 0.0, null);

					rowOrdering.addUnorderedOptimizable(this);
				}
				else
				{
// GemStone changes BEGIN
					if (!currentConglomerateDescriptor.isIndex()) {
					  optimizer.trace(Optimizer.SCANNING_HEAP_FULL_MATCH_ON_UNIQUE_KEY,
					      0, 0, 0.0, null);
					}
					else {
					  optimizer.trace(Optimizer.HASH_INDEX_FULL_MATCH_ON_UNIQUE_KEY,
					      0, 0, 0.0, null);
					}
					/* (original code)
					optimizer.trace(Optimizer.SCANNING_HEAP_FULL_MATCH_ON_UNIQUE_KEY,
									 0, 0, 0.0, null);
					*/
// GemStone changes END
				}
			}
			else
			{
// GemStone changes BEGIN
			  /* (original code)
				IndexRowGenerator irg =
							currentConglomerateDescriptor.getIndexDescriptor();
			  */
// GemStone changes END

				int[] baseColumnPositions = irg.baseColumnPositions();
				boolean[] isAscending = irg.isAscending();

				for (int i = 0; i < baseColumnPositions.length; i++)
				{
					/*
					** Don't add the column to the ordering if it's already
					** an ordered column.  This can happen in the following
					** case:
					**
					**		create index ti on t(x, y);
					**		select * from t where x = 1 order by y;
					**
					** Column x is always ordered, so we want to avoid the
					** sort when using index ti.  This is accomplished by
					** making column y appear as the first ordered column
					** in the list.
					*/
					if ( ! rowOrdering.orderedOnColumn(isAscending[i] ?
													RowOrdering.ASCENDING :
													RowOrdering.DESCENDING,
													getTableNumber(),
													baseColumnPositions[i]))
					{
						rowOrdering.nextOrderPosition(isAscending[i] ?
													RowOrdering.ASCENDING :
													RowOrdering.DESCENDING);

						rowOrdering.addOrderedColumn(isAscending[i] ?
													RowOrdering.ASCENDING :
													RowOrdering.DESCENDING,
													getTableNumber(),
													baseColumnPositions[i]);
					}
				}
			}	
		}		
               
		ap.setConglomerateDescriptor(currentConglomerateDescriptor);
		
		return currentConglomerateDescriptor != null;
	}

	/** Tell super-class that this Optimizable can be ordered */
	@Override
  protected boolean canBeOrdered()
	{
		return true;
	}

	/**
	 * @see com.pivotal.gemfirexd.internal.iapi.sql.compile.Optimizable#optimizeIt
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public CostEstimate optimizeIt(
				Optimizer optimizer,
				OptimizablePredicateList predList,
				CostEstimate outerCost,
				RowOrdering rowOrdering)
			throws StandardException
	{
          LanguageConnectionContext lcc = getLanguageConnectionContext();
          CompilerContext cc = getCompilerContext();
          boolean queryHDFS = false;
          if (lcc != null) {
            queryHDFS = lcc.getQueryHDFS();
          }
          if (cc != null && cc.getHasQueryHDFS()) {
            queryHDFS = cc.getQueryHDFS();
          }
          
          ConglomerateDescriptor cd = getCurrentAccessPath().getConglomerateDescriptor();          
              
          String indexType = null;
          if (cd != null && cd.getIndexDescriptor() != null
              && cd.getIndexDescriptor().getIndexDescriptor() != null) {
            indexType = cd.getIndexDescriptor().indexType();
          }
          
          // Not considering using index when queryHDFS == true (#48983)
          if (!(queryHDFS && 
              (GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE.equals(indexType)))) {
  		optimizer.costOptimizable(
  							this,
  							tableDescriptor,
  							getCurrentAccessPath().getConglomerateDescriptor(),
  							predList,
  							outerCost);
  	  }

		// The cost that we found from the above call is now stored in the
		// cost field of this FBT's current access path.  So that's the
		// cost we want to return here.
		return getCurrentAccessPath().getCostEstimate();
	}

	/** @see Optimizable#getTableDescriptor */
	@Override
  public TableDescriptor getTableDescriptor()
	{
		return tableDescriptor;
	}


	/** @see Optimizable#isMaterializable 
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean isMaterializable()
		throws StandardException
	{
		/* base tables are always materializable */
		return true;
	}


	/**
	 * @see Optimizable#pushOptPredicate
	 *
	 * @exception StandardException		Thrown on error
	 */

	@Override
  public boolean pushOptPredicate(OptimizablePredicate optimizablePredicate)
		throws StandardException
	{
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(optimizablePredicate instanceof Predicate,
				"optimizablePredicate expected to be instanceof Predicate");
		}

		/* Add the matching predicate to the restrictionList */
		restrictionList.addPredicate((Predicate) optimizablePredicate);

		return true;
	}

	/**
	 * @see Optimizable#pullOptPredicates
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public void pullOptPredicates(
								OptimizablePredicateList optimizablePredicates,
					/*GemStone Changes Begin*/	JBitSet outerTables	/*GemStone Changes END*/	)
					throws StandardException
	{
		for (int i = restrictionList.size() - 1; i >= 0; i--) {
			optimizablePredicates.addOptPredicate(
									restrictionList.getOptPredicate(i));
			restrictionList.removeOptPredicate(i);
		}
	}

	/** 
	 * @see Optimizable#isCoveringIndex
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean isCoveringIndex(ConglomerateDescriptor cd) throws StandardException
	{
		boolean coveringIndex = true;
		IndexRowGenerator	irg;
		int[]				baseCols;
		int					colPos;

		/* You can only be a covering index if you're an index */
		if ( ! cd.isIndex())
			return false;

		irg = cd.getIndexDescriptor();
		baseCols = irg.baseColumnPositions();

		/* First we check to see if this is a covering index */
		int rclSize = resultColumns.size();
		for (int index = 0; index < rclSize; index++)
		{
			ResultColumn rc = (ResultColumn) resultColumns.elementAt(index);

			/* Ignore unreferenced columns */
			if (! rc.isReferenced())
			{
				continue;
			}

			/* Ignore constants - this can happen if all of the columns
			 * were projected out and we ended up just generating
			 * a "1" in RCL.doProject().
			 */
			if (rc.getExpression() instanceof ConstantNode)
			{
				continue;
			}

			coveringIndex = false;

			colPos = rc.getColumnPosition();

			/* Is this column in the index? */
			for (int i = 0; i < baseCols.length; i++)
			{
				if (colPos == baseCols[i])
				{
					coveringIndex = true;
					break;
				}
			}

			/* No need to continue if the column was not in the index */
			if (! coveringIndex)
			{
				break;
			}
		}
		return coveringIndex;
	}

  // GemStone changes BEGIN
  final static String validQueryHintProperites = Constants.QueryHints.index
      + "," + Constants.QueryHints.constraint + ","
      + Constants.QueryHints.joinStrategy + ","
      + Constants.QueryHints.hashInitialCapacity + ","
      + Constants.QueryHints.hashLoadFactor + ","
      + Constants.QueryHints.hashMaxCapacity + ","
      + Constants.QueryHints.bulkFetch + ","
      + Constants.QueryHints.withSecondaries + ","
      + Constants.QueryHints.queryHDFS + ","
      + Constants.QueryHints.executionEngine;

  // GemStone changes END
	
	/** @see Optimizable#verifyProperties 
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public void verifyProperties(DataDictionary dDictionary)
		throws StandardException
	{
		if (tableProperties == null)
		{
			return;
		}
		/* Check here for:
		 *		invalid properties key
		 *		index and constraint properties
		 *		non-existent index
		 *		non-existent constraint
		 *		invalid joinStrategy
		 *		invalid value for hashInitialCapacity
		 *		invalid value for hashLoadFactor
		 *		invalid value for hashMaxCapacity
		 */
		
		boolean indexSpecified = false;
		boolean constraintSpecified = false;
		ConstraintDescriptor consDesc = null;
		Enumeration e = tableProperties.keys();

			StringUtil.SQLEqualsIgnoreCase(tableDescriptor.getSchemaName(), 
										   "SYS");
		while (e.hasMoreElements())
		{
			String key = (String) e.nextElement();
			String value = (String) tableProperties.get(key);

                        /*(original code) if (key.equals("index"))*/
			if (key.equals(Constants.QueryHints.index.name()))
			{
				// User only allowed to specify 1 of index and constraint, not both
				if (constraintSpecified)
				{
					throw StandardException.newException(SQLState.LANG_BOTH_FORCE_INDEX_AND_CONSTRAINT_SPECIFIED, 
								getBaseTableName());
				}
				indexSpecified = true;

				/* Validate index name - NULL means table scan */
				if (! StringUtil.SQLToUpperCase(value).equals("NULL"))
				{
					ConglomerateDescriptor cd = null;
					ConglomerateDescriptor[] cds = tableDescriptor.getConglomerateDescriptors();

					for (int index = 0; index < cds.length; index++)
					{
						cd = cds[index];
						String conglomerateName = cd.getConglomerateName();
						if (conglomerateName != null)
						{
							if (conglomerateName.equals(value))
							{
								break;
							}
						}
						// Not a match, clear cd
						cd = null;
					}

					// Throw exception if user specified index not found
					if (cd == null)
					{
						throw StandardException.newException(SQLState.LANG_INVALID_FORCED_INDEX1, 
										value, getBaseTableName());
					}
					/* Query is dependent on the ConglomerateDescriptor */
					getCompilerContext().createDependency(cd);
				}
			}
                        /*(original code) else if (key.equals("constraint"))*/
			else if (key.equals(Constants.QueryHints.constraint.name()))
			{
				// User only allowed to specify 1 of index and constraint, not both
				if (indexSpecified)
				{
					throw StandardException.newException(SQLState.LANG_BOTH_FORCE_INDEX_AND_CONSTRAINT_SPECIFIED, 
								getBaseTableName());
				}
				constraintSpecified = true;

				if (! StringUtil.SQLToUpperCase(value).equals("NULL"))
				{
					consDesc = 
						dDictionary.getConstraintDescriptorByName(
									tableDescriptor, (SchemaDescriptor)null, value,
									false);

					/* Throw exception if user specified constraint not found
					 * or if it does not have a backing index.
					 */
					if ((consDesc == null) || ! consDesc.hasBackingIndex())
					{
						throw StandardException.newException(SQLState.LANG_INVALID_FORCED_INDEX2, 
										value, getBaseTableName());
					}

					/* Query is dependent on the ConstraintDescriptor */
					getCompilerContext().createDependency(consDesc);
				}
			}
                        /*(original code) else if (key.equals("joinStrategy"))*/
			else if (key.equals(Constants.QueryHints.joinStrategy.name()))
			{
			        setUserSpecifiedJoinStrategy(value);
			}
			else if (key.equals(Constants.QueryHints.executionEngine.name()))
			{
			        setUserSpecifiedExecutionEngine(value);
			}
                        /*(original code) else if (key.equals("hashInitialCapacity"))*/
			else if (key.equals(Constants.QueryHints.hashInitialCapacity.name()))
			{
				initialCapacity = getIntProperty(value, key);

				// verify that the specified value is valid
				if (initialCapacity <= 0)
				{
					throw StandardException.newException(SQLState.LANG_INVALID_HASH_INITIAL_CAPACITY, 
							String.valueOf(initialCapacity));
				}
			}
                        /*(original code) else if (key.equals("hashLoadFactor"))*/
			else if (key.equals(Constants.QueryHints.hashLoadFactor.name()))
			{
				try
				{
					loadFactor = Float.valueOf(value).floatValue();
				}
				catch (NumberFormatException nfe)
				{
					throw StandardException.newException(SQLState.LANG_INVALID_NUMBER_FORMAT_FOR_OVERRIDE, 
							value, key);
				}

				// verify that the specified value is valid
				if (loadFactor <= 0.0 || loadFactor > 1.0)
				{
					throw StandardException.newException(SQLState.LANG_INVALID_HASH_LOAD_FACTOR, 
							value);
				}
			}
                        /*(original code) else if (key.equals("hashMaxCapacity"))*/
			else if (key.equals(Constants.QueryHints.hashMaxCapacity.name()))
			{
				maxCapacity = getIntProperty(value, key);

				// verify that the specified value is valid
				if (maxCapacity <= 0)
				{
					throw StandardException.newException(SQLState.LANG_INVALID_HASH_MAX_CAPACITY, 
							String.valueOf(maxCapacity));
				}
			}
			/*(original code) else if (key.equals("bulkFetch"))*/
			else if (key.equals(Constants.QueryHints.bulkFetch.name()))
			{
				bulkFetch = getIntProperty(value, key);

				// verify that the specified value is valid
				if (bulkFetch <= 0)
				{
					throw StandardException.newException(SQLState.LANG_INVALID_BULK_FETCH_VALUE, 
							String.valueOf(bulkFetch));
				}
			
				// no bulk fetch on updatable scans
				if (forUpdate())
				{
					throw StandardException.newException(SQLState.LANG_INVALID_BULK_FETCH_UPDATEABLE);
				}
			}
                        // GemStone changes BEGIN
                        else if (key.equals(Constants.QueryHints.withSecondaries.name())) {
                          explicitSecondaryBucketSet = true;
                          includeSecondaryBuckets = Misc.parseBoolean(value);
                          final CompilerContext cc = getCompilerContext();
                          cc.setOptimizeForWrite(!includeSecondaryBuckets);
                          cc.setWithSecondaries(includeSecondaryBuckets);
                          if (SanityManager.DEBUG) {
                            if (GemFireXDUtils.TraceAggreg) {
                              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
                                  this.tableName + " includeSecondaryBuckets="
                                      + includeSecondaryBuckets + " explicitSecondaryBucketSet="
                                      + explicitSecondaryBucketSet);
                  
                            }
                          }
                        }
                        else if (key.equals(Constants.QueryHints.queryHDFS.name())) {
                          queryHDFS = Misc.parseBoolean(value);
                          getCompilerContext().setQueryHDFS(queryHDFS);
                          getCompilerContext().setHasQueryHDFS(true);
                        }
                        // GemStone changes END
			else
			{
				// No other "legal" values at this time
			  // GemStone changes BEGIN
				/*(original code) throw StandardException.newException(SQLState.LANG_INVALID_FROM_TABLE_PROPERTY, key,
					"index, constraint, joinStrategy");*/
                                throw StandardException.newException(
                                    SQLState.LANG_INVALID_FROM_TABLE_PROPERTY, key,
                                    validQueryHintProperites);
                          // GemStone changes END
			}
		}

		/* If user specified a non-null constraint name(DERBY-1707), then  
		 * replace it in the properties list with the underlying index name to 
		 * simplify the code in the optimizer.
		 * NOTE: The code to get from the constraint name, for a constraint
		 * with a backing index, to the index name is convoluted.  Given
		 * the constraint name, we can get the conglomerate id from the
		 * ConstraintDescriptor.  We then use the conglomerate id to get
		 * the ConglomerateDescriptor from the DataDictionary and, finally,
		 * we get the index name (conglomerate name) from the ConglomerateDescriptor.
		 */
		if (constraintSpecified && consDesc != null)
		{
			ConglomerateDescriptor cd = 
				dDictionary.getConglomerateDescriptor(
					consDesc.getConglomerateId());
			String indexName = cd.getConglomerateName();

			tableProperties.remove("constraint");
			tableProperties.put("index", indexName);
		}
	}

	/** @see Optimizable#getBaseTableName */
	@Override
  public String getBaseTableName()
	{
		return tableName.getTableName();
	}

	/** @see Optimizable#startOptimizing */
	@Override
  public void startOptimizing(Optimizer optimizer, RowOrdering rowOrdering)
	{
		AccessPath ap = getCurrentAccessPath();
		AccessPath bestAp = getBestAccessPath();
		AccessPath bestSortAp = getBestSortAvoidancePath();

		ap.setConglomerateDescriptor((ConglomerateDescriptor) null);
		bestAp.setConglomerateDescriptor((ConglomerateDescriptor) null);
		bestSortAp.setConglomerateDescriptor((ConglomerateDescriptor) null);
		ap.setCoveringIndexScan(false);
		bestAp.setCoveringIndexScan(false);
		bestSortAp.setCoveringIndexScan(false);
		ap.setLockMode(0);
		bestAp.setLockMode(0);
		bestSortAp.setLockMode(0);

		/*
		** Only need to do this for current access path, because the
		** costEstimate will be copied to the best access paths as
		** necessary.
		*/
		CostEstimate costEstimate = getCostEstimate(optimizer);
		ap.setCostEstimate(costEstimate);

		/*
		** This is the initial cost of this optimizable.  Initialize it
		** to the maximum cost so that the optimizer will think that
		** any access path is better than none.
		*/
		costEstimate.setCost(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

		super.startOptimizing(optimizer, rowOrdering);
	}

	/** @see Optimizable#convertAbsoluteToRelativeColumnPosition */
	@Override
  public int convertAbsoluteToRelativeColumnPosition(int absolutePosition, ColumnReference ref)
	{
		return mapAbsoluteToRelativeColumnPosition(absolutePosition);
	}

	/**
	 * @see Optimizable#estimateCost
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public CostEstimate estimateCost(OptimizablePredicateList predList,
									ConglomerateDescriptor cd,
									CostEstimate outerCost,
									Optimizer optimizer,
									RowOrdering rowOrdering)
			throws StandardException
	{
		double cost;
		boolean statisticsForTable = false;
		boolean statisticsForConglomerate = false;
		/* unknownPredicateList contains all predicates whose effect on
		 * cost/selectivity can't be calculated by the store.
		 */
		PredicateList unknownPredicateList = null;

		if (optimizer.useStatistics() && predList != null)
		{
			/* if user has specified that we don't use statistics,
			   pretend that statistics don't exist.
			*/
			statisticsForConglomerate = tableDescriptor.statisticsExist(cd);
			statisticsForTable = tableDescriptor.statisticsExist(null);
			unknownPredicateList = new PredicateList();
			predList.copyPredicatesToOtherList(unknownPredicateList);

		}

		AccessPath currentAccessPath = getCurrentAccessPath();
		JoinStrategy currentJoinStrategy = 
			currentAccessPath.getJoinStrategy();

		optimizer.trace(Optimizer.ESTIMATING_COST_OF_CONGLOMERATE,
						tableNumber, 0, 0.0, cd);

		/* Get the uniqueness factory for later use (see below) */
		double tableUniquenessFactor =
				optimizer.uniqueJoinWithOuterTable(predList);

		boolean oneRowResultSetForSomeConglom = isOneRowResultSet(predList);

// GemStone changes BEGIN
		CostEstimate costEstimate = getScratchCostEstimate(optimizer);
		// check for any OrListNodes in the predicate list
		// and optimize them separately and then remove them
		final int numPreds = predList != null ? predList.size() : 0;
		for (int i = 0; i < numPreds; i++) {
		  Predicate pred = (Predicate)predList.getOptPredicate(i);
		  BooleanConstantNode constTrue = null;
		  JoinStrategy nestedLoopStrategy = null;
		  if (pred.getAndNode() instanceof OrListNode) {
		    // TODO: PERF: if there are additional conditions apart from
		    // OR list then throw back exception to avoid considering OR
		    // list but we should add additional qualifiers to
		    // MultiColumnTableScanRS
		    if (numPreds > 1) {
		      // ignore if additional predicates are only dummy AND
		      // nodes with Boolean.TRUE
		      for (int j = 0; i < numPreds; j++) {
		        if (j == i) {
		          continue;
		        }
		        Predicate p = (Predicate)predList.getOptPredicate(j);
		        if (!p.getAndNode().getLeftOperand().isBooleanTrue() ||
		            !p.getAndNode().getRightOperand().isBooleanTrue()) {
		          throw StandardException.newException(
		              SQLState.INTERNAL_SKIP_ORLIST_OPTIMIZATION);
		        }
		      }
		    }
		    // optimize each AND portion separately
		    OrListNode orListNode = (OrListNode)pred.getAndNode();
		    // if orListNode has already been optimized then nothing
		    // to be done below
		    if (orListNode.hasOptimizedList()) {
		      // just add the previously evaluated costs for each
		      // section of the OR list
		      for (ElementSpecification element : orListNode
		          .getElements()) {
		        costEstimate.add(element.costEstimate, costEstimate);
		      }
		      continue;
		    }
		    // get all the conglomerates for the table
		    ConglomerateDescriptor[] cds = this.tableDescriptor
		        .getConglomerateDescriptors();
		    PredicateList newPredList;
		    Predicate newPred;
		    JBitSet tableMap = pred.getReferencedMap();
		    CostEstimate bestCost;
		    ConglomerateDescriptor bestCostConglom;
		    PredicateList bestPredList;
		    Predicate bestPred;
		    if (nestedLoopStrategy == null) {
		      nestedLoopStrategy = new NestedLoopJoinStrategy();
		    }
		    for (Object qtn : orListNode.getValueNodes()) {
		      // make a copy of the original predicateList
		      newPredList = (PredicateList)getNodeFactory()
		          .getNode(C_NodeTypes.PREDICATE_LIST, getContextManager());
		      OrNode on = (OrNode)qtn;
                      ValueNode valNode = on.getLeftOperand();
                      ArrayDeque<ValueNode> stack = new ArrayDeque<ValueNode>();
		      for (;;) {
		        if (valNode instanceof AndNode) {
		          // ignore a non-terminal AndNode assuming that it has
		          // only further AndNodes or BinaryRelationalOperators
		          final AndNode an = (AndNode)valNode;
		          valNode = an.getLeftOperand();
		          if (!(valNode instanceof AndNode)) {
		            newPred = (Predicate)getNodeFactory().getNode(
		                C_NodeTypes.PREDICATE, an,
		                tableMap, getContextManager());
		            newPredList.addOptPredicate(newPred);
		            valNode = an.getRightOperand();
		          }
		          else {
		            stack.push(an.getRightOperand());
		          }
		          continue;
		        }
		        else if (valNode instanceof BinaryRelationalOperatorNode) {
		          if (constTrue == null) {
		            constTrue = (BooleanConstantNode)getNodeFactory()
		                .getNode(C_NodeTypes.BOOLEAN_CONSTANT_NODE,
		                    Boolean.TRUE, getContextManager());
		          }
		          AndNode an = (AndNode)getNodeFactory().getNode(
		              C_NodeTypes.AND_NODE, valNode,
		              constTrue, getContextManager());
		          newPred = (Predicate)getNodeFactory().getNode(
		              C_NodeTypes.PREDICATE, an, tableMap,
		              getContextManager());
		          newPredList.addOptPredicate(newPred);
		          // terminal node reached
		        }
		        else {
		          SanityManager.ASSERT(
		              valNode instanceof BooleanConstantNode);
		          // terminal node reached
		        }
		        if (stack.isEmpty()) {
		          break;
		        }
		        else {
		          valNode = stack.pop();
		        }
		      }
		      bestCost = null;
		      bestCostConglom = null;
		      bestPredList = null;
		      // always want to use indexes that will use nested
		      // loop join strategy
		      currentAccessPath.setJoinStrategy(nestedLoopStrategy);
		      // get the best cost estimate
		      for (ConglomerateDescriptor desc : cds) {
		        // change the current conglomerate
		        currentAccessPath.setConglomerateDescriptor(desc);
		        // check if the strategy is feasible
		        if (!feasibleJoinStrategy(newPredList, optimizer) ||
		            !newPredList.useful(this, desc)) {
		          continue;
		        }
		        CostEstimate elemCost = estimateCost(newPredList,
		            desc, outerCost, optimizer, rowOrdering);
		        if (bestCost == null || elemCost.compare(bestCost) < 0) {
		          bestCost = elemCost.cloneMe();
		          bestCostConglom = desc;
		          // copy the predicate list to the bestPredList since
		          // the flags etc. of the predicates may change for
		          // other conglomerates in further estimateCost calls
		          if (bestPredList == null) {
		            bestPredList = (PredicateList)getNodeFactory().getNode(
		                C_NodeTypes.PREDICATE_LIST, getContextManager());
		          }
		          else {
		            bestPredList.clearAllPredicates();
		          }
		          for (Object p : newPredList) {
		            newPred = (Predicate)p;
		            bestPred = (Predicate)getNodeFactory().getNode(
		                C_NodeTypes.PREDICATE, newPred.getAndNode(),
		                tableMap, getContextManager());
		            bestPred.copyFields(newPred);
		            bestPredList.addPredicate(bestPred);
		          }
		        }
		      }
		      if (bestCostConglom == null || !bestCostConglom.isIndex()) {
		        // if index cannot be used for an OR element, then
		        // do the whole compilation again but with OrList
		        // optimization turned off
		        throw StandardException.newException(
		            SQLState.INTERNAL_SKIP_ORLIST_OPTIMIZATION);
		      }
		      // relevant portion from changeAccessPath()
		      bestPredList.removeRedundantPredicates();
		      final boolean isOneRowRS = isOneRowResultSet(
		          bestCostConglom, bestPredList);
		      int bulkFetch = UNSET;
		      boolean multiProbing = false;
		      for (Object p : bestPredList) {
		        pred = (Predicate)p;
		        if (pred.isInListProbePredicate() && pred.isStartKey()) {
		          multiProbing = true;
		          break;
		        }
		      }
		      if (!multiProbing && !forUpdate() &&
		          !isOneRowRS && getLevel() == 0) {
		        bulkFetch = getDefaultBulkFetch();
		      }

		      // add the cost to the overall cost
		      costEstimate.add(bestCost, costEstimate);
		      // add predicate list for each element
		      orListNode.addElement(bestCostConglom, bestPredList,
		          bestCost, bulkFetch, multiProbing);
		    }
		    // at this point we have found the best cost for all the
		    // elements of the OR list; currently cannot create a
		    // sort avoidance path with OR list
		    // TODO: PERF: create a sort avoidance path if all the
		    // elements in the OR list refer to the same column and
		    // avoid sort by merging across them

		    // reset the join strategy and conglomerate descriptor
		    currentAccessPath.setConglomerateDescriptor(cd);
		    currentAccessPath.setJoinStrategy(currentJoinStrategy);
		  }
		}

		//Asif:If the PredList at this point contains an ORListNode & it forms the start Key for the conglomerate
		// we override the derby costing to select this plan
		if(predList != null && predList.size() == 1 ) {
		  Predicate pred = (Predicate)predList.getOptPredicate(0);
		  if(pred.getAndNode() instanceof OrListNode) {
		    OrListNode orln = (OrListNode)pred.andNode;
		    long numRows = 0;
		    if(cd == orln.elements.get(0).conglom) {
		      //Get the total inner row count
		      for(int i = 0 ; i < orln.elements.size(); ++i) {
		        numRows += orln.elements.get(i).costEstimate.getEstimatedRowCount();		        
		      }
		      costEstimate.setCost(outerCost.getEstimatedRowCount() * Double.MIN_VALUE,numRows*outerCost.getEstimatedRowCount() , numRows);
		      return costEstimate;
		    }
		  }
		  
		  
		}
		
// GemStone changes END
		/* Get the predicates that can be used for scanning the base table */
		baseTableRestrictionList.removeAllElements();

		currentJoinStrategy.getBasePredicates(predList,	
									   baseTableRestrictionList,
									   this);
									
		/* RESOLVE: Need to figure out how to cache the StoreCostController */
		StoreCostController scc = getStoreCostController(cd);

// GemStone changes BEGIN
		/* (original code)
		CostEstimate costEstimate = getScratchCostEstimate(optimizer);
		*/

		/* First, get the cost for one scan */

		/* Does the conglomerate match at most one row? */
		// treat even non-unique indexes in the same way; cost
		// controller will already adjust the cost of getFetchFromFullKeyCost
		// for the non-unique case
		final int oneRowResult = isOneRowResultSet(cd,
		    this.baseTableRestrictionList, false);
		if (oneRowResult != ONE_ROW_NONE)
		/* (original code)
		if (isOneRowResultSet(cd, baseTableRestrictionList))
		*/
// GemStone changes END
		{
			/*
			** Tell the RowOrdering that this optimizable is always ordered.
			** It will figure out whether it is really always ordered in the
			** context of the outer tables and their orderings.
			*/
// GemStone changes BEGIN
			if (oneRowResult == ONE_ROW_UNIQUE) {
			  rowOrdering.optimizableAlwaysOrdered(this);
			}
			/* (original code)
			rowOrdering.optimizableAlwaysOrdered(this);
			*/
// GemStone changes END

			singleScanRowCount = 1.0;
			int matchGroupByCols = this.matchGroupByColumns(cd);

			/* Yes, the cost is to fetch exactly one row */
			// RESOLVE: NEED TO FIGURE OUT HOW TO GET REFERENCED COLUMN LIST,
			// FIELD STATES, AND ACCESS TYPE
			scc.getFetchFromFullKeyCost(
										(FormatableBitSet) null,
// GemStone changes BEGIN
										currentJoinStrategy.scanCostType(),
										costEstimate
										);
			                                                          
										/* (original code)
										0);
										*/
// GemStone changes END

			optimizer.trace(Optimizer.MATCH_SINGLE_ROW_COST,
							tableNumber, 0, costEstimate.getEstimatedCost(), null);

			//costEstimate.setCost(cost, 1.0d, 1.0d);

			/*
			** Let the join strategy decide whether the cost of the base
			** scan is a single scan, or a scan per outer row.
			** NOTE: The multiplication should only be done against the
			** total row count, not the singleScanRowCount.
			*/
			double newCost = costEstimate.getEstimatedCost();

			if (currentJoinStrategy.multiplyBaseCostByOuterRows())
			{
				newCost *= outerCost.rowCount();
			}
// GemStone changes BEGIN
			// for hash join strategy, need to add some cost for
			// building the hash table and keeping it in memory
			// also always prefer exising index over hash so add
			// in tree depth
			if (currentJoinStrategy.isHashJoin()) {
			  double depth = Math.log(baseRowCount()) / Math.log(2);
                          newCost *= (2.0d + depth);
			  newCost += 1000.0d;
			}
// GemStone changes END

			costEstimate.setCost(
				newCost,
				costEstimate.rowCount() * outerCost.rowCount(),
				costEstimate.singleScanRowCount());

			/*
			** Choose the lock mode.  If the start/stop conditions are
			** constant, choose row locking, because we will always match
			** the same row.  If they are not constant (i.e. they include
			** a join), we decide whether to do row locking based on
			** the total number of rows for the life of the query.
			*/
			boolean constantStartStop = true;
			for (int i = 0; i < predList.size(); i++)
			{
				OptimizablePredicate pred = predList.getOptPredicate(i);

				/*
				** The predicates are in index order, so the start and
				** stop keys should be first.
				*/
				if ( ! (pred.isStartKey() || pred.isStopKey()))
				{
					break;
				}

				/* Stop when we've found a join */
				if ( ! pred.getReferencedMap().hasSingleBitSet())
				{
					constantStartStop = false;
					break;
				}
			}

			if (constantStartStop)
			{
				currentAccessPath.setLockMode(
											TransactionController.MODE_RECORD);

				optimizer.trace(Optimizer.ROW_LOCK_ALL_CONSTANT_START_STOP,
								0, 0, 0.0, null);
			}
			else
			{
				setLockingBasedOnThreshold(optimizer, costEstimate.rowCount());
			}

			optimizer.trace(Optimizer.COST_OF_N_SCANS, 
							tableNumber, 0, outerCost.rowCount(), costEstimate);

			/* Add in cost of fetching base row for non-covering index */
// GemStone changes BEGIN
			// GemFireXD indexes now don't have separate cost for this
			// since key fetch itself requires reading a base table
			// row. The cost controllers themselves adjust the cost
			// accordingly since it is included in key fetch cost.
			/* (original code)
			if (cd.isIndex() && ( ! isCoveringIndex(cd) ) )
			{
				double singleFetchCost =
						getBaseCostController().getFetchFromRowLocationCost(
																(FormatableBitSet) null,
																0);
				cost = singleFetchCost * costEstimate.rowCount();

				costEstimate.setEstimatedCost(
								costEstimate.getEstimatedCost() + cost);

				optimizer.trace(Optimizer.NON_COVERING_INDEX_COST,
								tableNumber, 0, cost, null);
			}
			*/
// GemStone changes END
		}
		else
		{
			/* Conglomerate might match more than one row */

			/*
			** Some predicates are good for start/stop, but we don't know
			** the values they are being compared to at this time, so we
			** estimate their selectivity in language rather than ask the
			** store about them .  The predicates on the first column of
			** the conglomerate reduce the number of pages and rows scanned.
			** The predicates on columns after the first reduce the number
			** of rows scanned, but have a much smaller effect on the number
			** of pages scanned, so we keep track of these selectivities in
			** two separate variables: extraFirstColumnSelectivity and
			** extraStartStopSelectivity. (Theoretically, we could try to
			** figure out the effect of predicates after the first column
			** on the number of pages scanned, but it's too hard, so we
			** use these predicates only to reduce the estimated number of
			** rows.  For comparisons with known values, though, the store
			** can figure out exactly how many rows and pages are scanned.)
			**
			** Other predicates are not good for start/stop.  We keep track
			** of their selectvities separately, because these limit the
			** number of rows, but not the number of pages, and so need to
			** be factored into the row count but not into the cost.
			** These selectivities are factored into extraQualifierSelectivity.
			**
			** statStartStopSelectivity (using statistics) represents the 
			** selectivity of start/stop predicates that can be used to scan 
			** the index. If no statistics exist for the conglomerate then 
			** the value of this variable remains at 1.0
			** 
			** statCompositeSelectivity (using statistics) represents the 
			** selectivity of all the predicates (including NonBaseTable 
			** predicates). This represents the most educated guess [among 
			** all the wild surmises in this routine] as to the number
			** of rows that will be returned from this joinNode.
			** If no statistics exist on the table or no statistics at all
			** can be found to satisfy the predicates at this join opertor,
			** then statCompositeSelectivity is left initialized at 1.0
			*/
			double extraFirstColumnSelectivity = 1.0d;
			double extraStartStopSelectivity = 1.0d;
			double extraQualifierSelectivity = 1.0d;
			double extraNonQualifierSelectivity = 1.0d;
			double statStartStopSelectivity = 1.0d;
			double statCompositeSelectivity = 1.0d;

			int	   numExtraFirstColumnPreds = 0;
			int	   numExtraStartStopPreds = 0;
			int	   numExtraQualifiers = 0;
			int	   numExtraNonQualifiers = 0;

			/*
			** It is possible for something to be a start or stop predicate
			** without it being possible to use it as a key for cost estimation.
			** For example, with an index on (c1, c2), and the predicate
			** c1 = othertable.c3 and c2 = 1, the comparison on c1 is with
			** an unknown value, so we can't pass it to the store.  This means
			** we can't pass the comparison on c2 to the store, either.
			**
			** The following booleans keep track of whether we have seen
			** gaps in the keys we can pass to the store.
			*/
			boolean startGap = false;
			boolean stopGap = false;
			boolean seenFirstColumn = false;

			/*
			** We need to figure out the number of rows touched to decide
			** whether to use row locking or table locking.  If the start/stop
			** conditions are constant (i.e. no joins), the number of rows
			** touched is the number of rows per scan.  But if the start/stop
			** conditions contain a join, the number of rows touched must
			** take the number of outer rows into account.
			*/
			boolean constantStartStop = true;
			boolean startStopFound = false;

			/* Count the number of start and stop keys */
			int startKeyNum = 0;
			int stopKeyNum = 0;
			OptimizablePredicate pred;
			int predListSize;
			int numStartKeyAllTypes =0;
			int numStopKeyAllTypes =0;
			
			if (predList != null)
				predListSize = baseTableRestrictionList.size();
			else
				predListSize = 0;

			int startStopPredCount = 0;
			ColumnReference firstColumn = null;
			for (int i = 0; i < predListSize; i++)
			{
				pred = baseTableRestrictionList.getOptPredicate(i);
				boolean startKey = pred.isStartKey();
				boolean stopKey = pred.isStopKey();
				if (startKey || stopKey)
				{
					startStopFound = true;

					if ( ! pred.getReferencedMap().hasSingleBitSet())
					{
						constantStartStop = false;
					}

					boolean knownConstant =
						pred.compareWithKnownConstant(this, true);
					if (startKey)
					{
						if (knownConstant && ( ! startGap ) )
						{
							startKeyNum++;
  							if (unknownPredicateList != null)
  								unknownPredicateList.removeOptPredicate(pred);
						}
						else
						{
						  if(pred.compareWithParameterNode(this)) {
						    ++numStartKeyAllTypes;
						  }
							startGap = true;
						}
					}

					if (stopKey)
					{
						if (knownConstant && ( ! stopGap ) )
						{
							stopKeyNum++;
  							if (unknownPredicateList != null)
  								unknownPredicateList.removeOptPredicate(pred);
						}
						else
						{
						  if(pred.compareWithParameterNode(this)) {
                ++numStopKeyAllTypes;
              }
							stopGap = true;
						}
					}

					/* If either we are seeing startGap or stopGap because start/stop key is
					 * comparison with non-constant, we should multiply the selectivity to
					 * extraFirstColumnSelectivity.  Beetle 4787.
					 */
					if (startGap || stopGap)
					{
						// Don't include redundant join predicates in selectivity calculations
						if (baseTableRestrictionList.isRedundantPredicate(i))
							continue;

						if (startKey && stopKey)
							startStopPredCount++;

						if (pred.getIndexPosition() == 0)
						{
							extraFirstColumnSelectivity *=
														pred.selectivity(this);
							if (! seenFirstColumn)
							{
								ValueNode relNode = ((Predicate) pred).getAndNode().getLeftOperand();
								if (relNode instanceof BinaryRelationalOperatorNode)
									firstColumn = ((BinaryRelationalOperatorNode) relNode).getColumnOperand(this);
								seenFirstColumn = true;
							}
						}
						else
						{
							extraStartStopSelectivity *= pred.selectivity(this);
							numExtraStartStopPreds++;
						}
					}
				}
				else
				{
					// Don't include redundant join predicates in selectivity calculations
					if (baseTableRestrictionList.isRedundantPredicate(i))
					{
						continue;
					}

					/* If we have "like" predicate on the first index column, it is more likely
					 * to have a smaller range than "between", so we apply extra selectivity 0.2
					 * here.  beetle 4387, 4787.
					 */
					if (pred instanceof Predicate)
					{
						ValueNode leftOpnd = ((Predicate) pred).getAndNode().getLeftOperand();
						if (firstColumn != null && leftOpnd instanceof LikeEscapeOperatorNode)
						{
							LikeEscapeOperatorNode likeNode = (LikeEscapeOperatorNode) leftOpnd;
//					                     GemStone changes begin
							if (likeNode.getLeftOperand().requiresTypeFromContext() && 
							    !likeNode.getLeftOperand().isParameterizedConstantNode())
							{
//                                                        GemStone changes end
								ValueNode receiver = ((TernaryOperatorNode) likeNode).getReceiver();
								if (receiver instanceof ColumnReference)
								{
									ColumnReference cr = (ColumnReference) receiver;
									if (cr.getTableNumber() == firstColumn.getTableNumber() &&
										cr.getColumnNumber() == firstColumn.getColumnNumber())
										extraFirstColumnSelectivity *= 0.2;
								}
							}
						}
					}

					if (pred.isQualifier())
					{
						extraQualifierSelectivity *= pred.selectivity(this);
						numExtraQualifiers++;
					}
					else
					{
						extraNonQualifierSelectivity *= pred.selectivity(this);
						numExtraNonQualifiers++;
					}

					/*
					** Strictly speaking, it shouldn't be necessary to
					** indicate a gap here, since there should be no more
					** start/stop predicates, but let's do it, anyway.
					*/
					startGap = true;
					stopGap = true;
				}
			}

			if (unknownPredicateList != null)
			{
				statCompositeSelectivity = unknownPredicateList.selectivity(this);
				//GemStone changes BEGIN
			 /*	if (statCompositeSelectivity == -1.0d)
					statCompositeSelectivity = 1.0d;*/
			  if (statCompositeSelectivity == -1.0d) {
          statCompositeSelectivity = 1.0d;
			  }
				
			}
                        //GemStone changes BEGIN
			//soubhik2008123: We have enough info to derive cardinality
			//so statistics flag is set on. 
                        statisticsForConglomerate = true;
                        statisticsForTable = true;
                        //GemStone changes END

			if (seenFirstColumn && statisticsForConglomerate &&
				(startStopPredCount > 0))
			{
				statStartStopSelectivity = 
					tableDescriptor.selectivityForConglomerate(cd, startStopPredCount);
			}

			/*
			** Factor the non-base-table predicates into the extra
			** non-qualifier selectivity, since these will restrict the
			** number of rows, but not the cost.
			*/
			extraNonQualifierSelectivity *=
				currentJoinStrategy.nonBasePredicateSelectivity(this, predList);

			/* Create the start and stop key arrays, and fill them in */
			DataValueDescriptor[] startKeys;
			DataValueDescriptor[] stopKeys;

			if (startKeyNum > 0)
				startKeys = new DataValueDescriptor[startKeyNum];
			else
				startKeys = null;

			if (stopKeyNum > 0)
				stopKeys = new DataValueDescriptor[stopKeyNum];
			else
				stopKeys = null;

			startKeyNum = 0;
			stopKeyNum = 0;
			startGap = false;
			stopGap = false;

			/* If we have a probe predicate that is being used as a start/stop
			 * key then ssKeySourceInList will hold the InListOperatorNode
			 * from which the probe predicate was built.
			 */
			InListOperatorNode ssKeySourceInList = null;
			for (int i = 0; i < predListSize; i++)
			{
				pred = baseTableRestrictionList.getOptPredicate(i);
				boolean startKey = pred.isStartKey();
				boolean stopKey = pred.isStopKey();

				if (startKey || stopKey)
				{
					/* A probe predicate is only useful if it can be used as
					 * as a start/stop key for _first_ column in an index
					 * (i.e. if the column position is 0).  That said, we only
					 * allow a single start/stop key per column position in
					 * the index (see PredicateList.orderUsefulPredicates()).
					 * Those two facts combined mean that we should never have
					 * more than one probe predicate start/stop key for a given
					 * conglomerate.
					 */
					if (SanityManager.DEBUG)
					{
						if ((ssKeySourceInList != null) &&
							((Predicate)pred).isInListProbePredicate())
						{
							SanityManager.THROWASSERT(
							"Found multiple probe predicate start/stop keys" +
							" for conglomerate '" + cd.getConglomerateName() +
							"' when at most one was expected.");
						}
					}

					/* By passing "true" in the next line we indicate that we
					 * should only retrieve the underlying InListOpNode *if*
					 * the predicate is a "probe predicate".
					 */
					ssKeySourceInList = ((Predicate)pred).getSourceInList(true);
					boolean knownConstant = pred.compareWithKnownConstant(this, true);

					if (startKey)
					{
						if (knownConstant && ( ! startGap ) )
						{
							startKeys[startKeyNum] = pred.getCompareValue(this);
							startKeyNum++;
						}
						else
						{
							startGap = true;
						}
					}

					if (stopKey)
					{
						if (knownConstant && ( ! stopGap ) )
						{
							stopKeys[stopKeyNum] = pred.getCompareValue(this);
							stopKeyNum++;
						}
						else
						{
							stopGap = true;
						}
					}
				}
				else
				{
					startGap = true;
					stopGap = true;
				}
			}

			int startOperator;
			int stopOperator;

			if (baseTableRestrictionList != null)
			{
				startOperator = baseTableRestrictionList.startOperator(this);
				stopOperator = baseTableRestrictionList.stopOperator(this);
			}
			else
			{
				/*
				** If we're doing a full scan, it doesn't matter what the
				** start and stop operators are.
				*/
				startOperator = ScanController.NA;
				stopOperator = ScanController.NA;
			}

			/*
			** Get a row template for this conglomerate.  For now, just tell
			** it we are using all the columns in the row.
			*/
			DataValueDescriptor[] rowTemplate = 
                getRowTemplate(cd, getBaseCostController());
// GemStone changes BEGIN
			// check if GROUP BY is in the same order as column
			// order of ConglomerateDescriptor
			int matchedGroupingColumns = matchGroupByColumns(cd );
// GemStone changes END

			/* we prefer index than table scan for concurrency reason, by a small
			 * adjustment on estimated row count.  This affects optimizer's decision
			 * especially when few rows are in table. beetle 5006. This makes sense
			 * since the plan may stay long before we actually check and invalidate it.
			 * And new rows may be inserted before we check and invalidate the plan.
			 * Here we only prefer index that has start/stop key from predicates. Non-
			 * constant start/stop key case is taken care of by selectivity later.
			 */
			long baseRC = (startKeys != null || stopKeys != null) ? baseRowCount() : baseRowCount() + 5;
      int accessType ;
      boolean isFullStartStopKeyForIndex = false; 
      /** 
       * equality uses index if covering in the
       * "if" condition above (isOneRowResultSet)
       * but an IN (...) list might get converted
       * into OR based equality and in such case
       * lets attempt to use a HashIndex using this parameter.
       * 
       * Note: This is different than scanType which is dependent on JoinStrategy.   
       */
      if(ssKeySourceInList != null &&
          ( ssKeySourceInList.getLeftOperand() instanceof ColumnReference &&
              ssKeySourceInList.getRightOperandList().containsOnlyConstantAndParamNodes() )
              ) {
        accessType = StoreCostController.STORECOST_SCAN_SET;
      }else if(startStopFound /*|| isCoveringIndex(cd)*/) {
        accessType = StoreCostController.STORECOST_SCAN_NORMAL;
        int numIndexCols= 0;
        isFullStartStopKeyForIndex = cd.isIndex() 
            && ( (numIndexCols =cd.getIndexDescriptor().baseColumnPositions().length) == numStartKeyAllTypes
            || numIndexCols == numStopKeyAllTypes);
        if(isFullStartStopKeyForIndex) {
          accessType = StoreCostController.STORECOST_SCAN_INDEX_FULL_KEY;
        }
            
      }else {
        accessType = 0;        
      }
			scc.getScanCost(
					currentJoinStrategy.scanCostType(),
					baseRC,
                    1,
					forUpdate(),
					(FormatableBitSet) null,
					rowTemplate,
					startKeys,
					startOperator,
					stopKeys,
					stopOperator,
					false,
					accessType,
					
// GemStone changes END
					costEstimate);

// GemStone changes BEGIN
			// if cost is Double.MAX_VALUE then it indicates that
			// this conglomerate should not be considered
			final boolean estimateIsMax = (costEstimate
			    .getEstimatedCost() == Double.MAX_VALUE);
// GemStone changes END
			/* initialPositionCost is the first part of the index scan cost we get above.
			 * It's the cost of initial positioning/fetch of key.  So it's unrelated to
			 * row count of how many rows we fetch from index.  We extract it here so that
			 * we only multiply selectivity to the other part of index scan cost, which is
			 * nearly linear, to make cost calculation more accurate and fair, especially
			 * compared to the plan of "one row result set" (unique index). beetle 4787.
			 */
			double initialPositionCost = 0.0;
			if (cd.isIndex() && !estimateIsMax /* GemStone addition */)
			{
			        //GemStone changes BEGIN
			        //orignal initialPositionCost = scc.getFetchFromFullKeyCost((FormatableBitSet) null, 0);
				initialPositionCost = scc.getFetchFromFullKeyCost((FormatableBitSet) null, 0, null);
				//GemStone changes END
				/* oneRowResultSetForSomeConglom means there's a unique index, but certainly
				 * not this one since we are here.  If store knows this non-unique index
				 * won't return any row or just returns one row (eg., the predicate is a
				 * comparison with constant or almost empty table), we do minor adjustment
				 * on cost (affecting decision for covering index) and rc (decision for
				 * non-covering). The purpose is favoring unique index. beetle 5006.
				 */
				if (oneRowResultSetForSomeConglom && costEstimate.rowCount() <= 1)
				{
					costEstimate.setCost(costEstimate.getEstimatedCost() * 2,
										 costEstimate.rowCount() + 2,
										 costEstimate.singleScanRowCount() + 2);
				}
			
				
			}

			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN1,
							tableNumber, 0, 0.0, cd);
			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN2,
							tableNumber, 0, 0.0, costEstimate);
			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN3,
							numExtraFirstColumnPreds, 0, 
							extraFirstColumnSelectivity, null);
			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN4,
							numExtraStartStopPreds, 0, 
							extraStartStopSelectivity, null);
			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN7,
							startStopPredCount, 0,
							statStartStopSelectivity, null);
			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN5,
							numExtraQualifiers, 0, 
							extraQualifierSelectivity, null);
			optimizer.trace(Optimizer.COST_OF_CONGLOMERATE_SCAN6,
							numExtraNonQualifiers, 0, 
							extraNonQualifierSelectivity, null);
// GemStone changes BEGIN
			if (estimateIsMax) {
			  if (cd.isIndex()) {
			    optimizer.trace(Optimizer.COST_OF_NONCOVERING_INDEX,
			        tableNumber, 0, 0.0, costEstimate);
			  }
			  // Put the base predicates back in the predicate list
			  currentJoinStrategy.putBasePredicates(predList,
			      baseTableRestrictionList);
			  return costEstimate;
			}
// GemStone changes END
			/* initial row count is the row count without applying
			   any predicates-- we use this at the end of the routine
			   when we use statistics to recompute the row count.
			*/
			double initialRowCount = costEstimate.rowCount();

			if (statStartStopSelectivity != 1.0d)
			{
                                /* Note: resolve defect #47196, without affecting 
                                 * @see com.pivotal.gemfirexd.jdbc.LocalCSLMIndexTest.testBug43981_sortAvoidance()
                                 * 
                                 * A. indexCoverSort finds that Do current index have all orderby columns,
                                 * and if so, do not do anything.
                                 * TODO: This can be further improved by verifying that do this index
                                 * also have all predicates?
                                 * 
                                 * B. isOneRowResultSet indicates an index matching with predicate
                                 * We should normally select same over any other index, and thus force
                                 * selectivity 
                                 */
                                boolean forceSelectivity = false;
                                if (cd.isIndex() && statStartStopSelectivity < 1
                                    && statStartStopSelectivity > 0 && !indexCoverSort(cd, optimizer)) {
                                // get all the conglomerates for the table
                                ConglomerateDescriptor[] cds = this.tableDescriptor
                                    .getConglomerateDescriptors();
                                for (ConglomerateDescriptor cdi : cds) {
                                    if (isOneRowResultSet(cdi, this.baseTableRestrictionList, false) != ONE_ROW_NONE) {
                                      forceSelectivity = true;
                                      break;
                                    }
                                  }
                                }
				/*
				** If statistics exist use the selectivity computed 
				** from the statistics to calculate the cost. 
				** NOTE: we apply this selectivity to the cost as well
				** as both the row counts. In the absence of statistics
				** we only applied the FirstColumnSelectivity to the 
				** cost.
				*/
				costEstimate.setCost(
							 scanCostAfterSelectivity(costEstimate.getEstimatedCost(),
													  initialPositionCost,
													  forceSelectivity ? 1.0d : statStartStopSelectivity,
													  oneRowResultSetForSomeConglom),
							 costEstimate.rowCount() * statStartStopSelectivity,
							 costEstimate.singleScanRowCount() *
							 statStartStopSelectivity);
				optimizer.trace(Optimizer.COST_INCLUDING_STATS_FOR_INDEX,
								tableNumber, 0, 0.0, costEstimate);

			}
			else
			{
				/*
				** Factor in the extra selectivity on the first column
				** of the conglomerate (see comment above).
				** NOTE: In this case we want to apply the selectivity to both
				** the total row count and singleScanRowCount.
				*/
				if (extraFirstColumnSelectivity != 1.0d)
				{
					costEstimate.setCost(
						 scanCostAfterSelectivity(costEstimate.getEstimatedCost(),
												  initialPositionCost,
												  extraFirstColumnSelectivity,
												  oneRowResultSetForSomeConglom),
						 costEstimate.rowCount() * extraFirstColumnSelectivity,
						 costEstimate.singleScanRowCount() * extraFirstColumnSelectivity);
					
					optimizer.trace(Optimizer.COST_INCLUDING_EXTRA_1ST_COL_SELECTIVITY,
									tableNumber, 0, 0.0, costEstimate);
				}

				/* Factor in the extra start/stop selectivity (see comment above).
				 * NOTE: In this case we want to apply the selectivity to both
				 * the row count and singleScanRowCount.
				 */
				if (extraStartStopSelectivity != 1.0d)
				{
					costEstimate.setCost(
						costEstimate.getEstimatedCost(),
						costEstimate.rowCount() * extraStartStopSelectivity,
						costEstimate.singleScanRowCount() * extraStartStopSelectivity);

					optimizer.trace(Optimizer.COST_INCLUDING_EXTRA_START_STOP,
									tableNumber, 0, 0.0, costEstimate);
				}
			}

			/* If the start and stop key came from an IN-list "probe predicate"
			 * then we need to adjust the cost estimate.  The probe predicate
			 * is of the form "col = ?" and we currently have the estimated
			 * cost of probing the index a single time for "?".  But with an
			 * IN-list we don't just probe the index once; we're going to
			 * probe it once for every value in the IN-list.  And we are going
			 * to potentially return an additional row (or set of rows) for
			 * each probe.  To account for this "multi-probing" we take the
			 * costEstimate and multiply each of its fields by the size of
			 * the IN-list.
			 *
			 * Note: If the IN-list has duplicate values then this simple
			 * multiplication could give us an elevated cost (because we
			 * only probe the index for each *non-duplicate* value in the
			 * IN-list).  But for now, we're saying that's okay.
			 */
			if (ssKeySourceInList != null)
			{
				int listSize = ssKeySourceInList.getRightOperandList().size();
				double rc = costEstimate.rowCount() * listSize;
				double ssrc = costEstimate.singleScanRowCount() * listSize;

				/* If multiplication by listSize returns more rows than are
				 * in the scan then just use the number of rows in the scan.
				 */
				costEstimate.setCost(
					costEstimate.getEstimatedCost() * listSize,
					rc > initialRowCount ? initialRowCount : rc,
					ssrc > initialRowCount ? initialRowCount : ssrc);
			}

			/*
			** Figure out whether to do row locking or table locking.
			**
			** If there are no start/stop predicates, we're doing full
			** conglomerate scans, so do table locking.
			*/
			if (! startStopFound)
			{
				currentAccessPath.setLockMode(
											TransactionController.MODE_TABLE);

				optimizer.trace(Optimizer.TABLE_LOCK_NO_START_STOP,
							    0, 0, 0.0, null);
			}
			else
			{
				/*
				** Figure out the number of rows touched.  If all the
				** start/stop predicates are constant, the number of
				** rows touched is the number of rows per scan.
				** This is also true for join strategies that scan the
				** inner table only once (like hash join) - we can
				** tell if we have one of those, because
				** multiplyBaseCostByOuterRows() will return false.
				*/
				double rowsTouched = costEstimate.rowCount();

				if ( (! constantStartStop) &&
					 currentJoinStrategy.multiplyBaseCostByOuterRows())
				{
					/*
					** This is a join where the inner table is scanned
					** more than once, so we have to take the number
					** of outer rows into account.  The formula for this
					** works out as follows:
					**
					**	total rows in table = r
					**  number of rows touched per scan = s
					**  number of outer rows = o
					**  proportion of rows touched per scan = s / r
					**  proportion of rows not touched per scan =
					**										1 - (s / r)
					**  proportion of rows not touched for all scans =
					**									(1 - (s / r)) ** o
					**  proportion of rows touched for all scans =
					**									1 - ((1 - (s / r)) ** o)
					**  total rows touched for all scans =
					**							r * (1 - ((1 - (s / r)) ** o))
					**
					** In doing these calculations, we must be careful not
					** to divide by zero.  This could happen if there are
					** no rows in the table.  In this case, let's do table
					** locking.
					*/
					double r = baseRowCount();
					if (r > 0.0)
					{
						double s = costEstimate.rowCount();
						double o = outerCost.rowCount();
						double pRowsNotTouchedPerScan = 1.0 - (s / r);
						double pRowsNotTouchedAllScans =
										Math.pow(pRowsNotTouchedPerScan, o);
						double pRowsTouchedAllScans =
										1.0 - pRowsNotTouchedAllScans;
						double rowsTouchedAllScans =
										r * pRowsTouchedAllScans;

						rowsTouched = rowsTouchedAllScans;
					}
					else
					{
						/* See comments in setLockingBasedOnThreshold */
						rowsTouched = optimizer.tableLockThreshold() + 1;
					}
				}

				setLockingBasedOnThreshold(optimizer, rowsTouched);
			}

			/*
			** If the index isn't covering, add the cost of getting the
			** base row.  Only apply extraFirstColumnSelectivity and extraStartStopSelectivity
			** before we do this, don't apply extraQualifierSelectivity etc.  The
			** reason is that the row count here should be the number of index rows
			** (and hence heap rows) we get, and we need to fetch all those rows, even
			** though later on some of them may be filtered out by other predicates.
			** beetle 4787.
			*/
// GemStone changes BEGIN
			// GemFireXD indexes now don't have separate cost for this
			// since key fetch itself requires reading a base table
			// row. The cost controllers themselves adjust the cost
			// accordingly since it is included in key fetch cost.
			/* (original code)
			if (cd.isIndex() && ( ! isCoveringIndex(cd) ) )
			{
				double singleFetchCost =
						getBaseCostController().getFetchFromRowLocationCost(
																(FormatableBitSet) null,
																0);

				cost = singleFetchCost * costEstimate.rowCount();

				costEstimate.setEstimatedCost(
								costEstimate.getEstimatedCost() + cost);

				optimizer.trace(Optimizer.COST_OF_NONCOVERING_INDEX,
								tableNumber, 0, 0.0, costEstimate);
			}
			*/
// GemStone changes END

			/* Factor in the extra qualifier selectivity (see comment above).
			 * NOTE: In this case we want to apply the selectivity to both
			 * the row count and singleScanRowCount.
			 */
			if (extraQualifierSelectivity != 1.0d)
			{
				costEstimate.setCost(
						costEstimate.getEstimatedCost(),
						costEstimate.rowCount() * extraQualifierSelectivity,
						costEstimate.singleScanRowCount() * extraQualifierSelectivity);

				optimizer.trace(Optimizer.COST_INCLUDING_EXTRA_QUALIFIER_SELECTIVITY,
								tableNumber, 0, 0.0, costEstimate);
			}

			singleScanRowCount = costEstimate.singleScanRowCount();

			/*
			** Let the join strategy decide whether the cost of the base
			** scan is a single scan, or a scan per outer row.
			** NOTE: In this case we only want to multiply against the
			** total row count, not the singleScanRowCount.
			** NOTE: Do not multiply row count if we determined that
			** conglomerate is a 1 row result set when costing nested
			** loop.  (eg, we will find at most 1 match when probing
			** the hash table.)
			*/
			double newCost = costEstimate.getEstimatedCost();
			double rowCount = costEstimate.rowCount();

			/*
			** RESOLVE - If there is a unique index on the joining
			** columns, the number of matching rows will equal the
			** number of outer rows, even if we're not considering the
			** unique index for this access path. To figure that out,
			** however, would require an analysis phase at the beginning
			** of optimization. So, we'll always multiply the number
			** of outer rows by the number of rows per scan. This will
			** give us a higher than actual row count when there is
			** such a unique index, which will bias the optimizer toward
			** using the unique index. This is probably OK most of the
			** time, since the optimizer would probably choose the
			** unique index, anyway. But it would be better if the
			** optimizer set the row count properly in this case.
			*/
			if (currentJoinStrategy.multiplyBaseCostByOuterRows())
			{
				newCost *= outerCost.rowCount();
			}
// GemStone changes BEGIN
			// for hash join strategy, need to add some cost for
			// building the hash table and keeping it in memory
			// also always prefer exising index over hash so add
			// in tree depth
			if (currentJoinStrategy.isHashJoin()) {
			  double depth = Math.log(baseRC) / Math.log(2);
			  newCost *= (2.0d + depth);
			  if (rowCount < 20) {
			    newCost += 1000.0d;
			  }
			}
// GemStone changes END

			rowCount *= outerCost.rowCount();
			initialRowCount *= outerCost.rowCount();


			/*
			** If this table can generate at most one row per scan,
			** the maximum row count is the number of outer rows.
			** NOTE: This does not completely take care of the RESOLVE
			** in the above comment, since it will only notice
			** one-row result sets for the current join order.
			*/
			if (oneRowResultSetForSomeConglom)
			{
				if (outerCost.rowCount() < rowCount)
				{
					rowCount = outerCost.rowCount();
				}
			}

			/*
			** The estimated cost may be too high for indexes, if the
			** estimated row count exceeds the maximum. Only do this
			** if we're not doing a full scan, and the start/stop position
			** is not constant (i.e. we're doing a join on the first column
			** of the index) - the reason being that this is when the
			** cost may be inaccurate.
			*/
			if (cd.isIndex() && startStopFound && ( ! constantStartStop ) )
			{
				/*
				** Does any table outer to this one have a unique key on
				** a subset of the joining columns? If so, the maximum number
				** of rows that this table can return is the number of rows
				** in this table times the number of times the maximum number
				** of times each key can be repeated.
				*/
				double scanUniquenessFactor = 
				  optimizer.uniqueJoinWithOuterTable(baseTableRestrictionList);
				if (scanUniquenessFactor > 0.0)
				{
					/*
					** A positive uniqueness factor means there is a unique
					** outer join key. The value is the reciprocal of the
					** maximum number of duplicates for each unique key
					** (the duplicates can be caused by other joining tables).
					*/
					double maxRows =
							(baseRowCount()) / scanUniquenessFactor;
					if (rowCount > maxRows)
					{
						/*
						** The estimated row count is too high. Adjust the
						** estimated cost downwards proportionately to
						** match the maximum number of rows.
						*/
						newCost *= (maxRows / rowCount);
					}
				}
			}

			/* The estimated total row count may be too high */
			if (tableUniquenessFactor > 0.0)
			{
				/*
				** A positive uniqueness factor means there is a unique outer
				** join key. The value is the reciprocal of the maximum number
				** of duplicates for each unique key (the duplicates can be
				** caused by other joining tables).
				*/
				double maxRows =
							(baseRowCount()) / tableUniquenessFactor;
				if (rowCount > maxRows)
				{
					/*
					** The estimated row count is too high. Set it to the
					** maximum row count.
					*/
					rowCount = maxRows;
				}
			}

			costEstimate.setCost(
				newCost,
				rowCount,
				costEstimate.singleScanRowCount());


			optimizer.trace(Optimizer.COST_OF_N_SCANS, 
							tableNumber, 0, outerCost.rowCount(), costEstimate);

			/*
			** Now figure in the cost of the non-qualifier predicates.
			** existsBaseTables have a row count of 1
			*/
			double rc = -1, src = -1;
			//GemStone changes BEGIN
			if (existsBaseTable())
				rc = src = 1;
			//GemStone changes END
			// don't factor in extraNonQualifierSelectivity in case of oneRowResultSetForSomeConglom
			// because "1" is the final result and the effect of other predicates already considered
			// beetle 4787
			else if (extraNonQualifierSelectivity != 1.0d)
			{
				rc = oneRowResultSetForSomeConglom ? costEstimate.rowCount() :
											costEstimate.rowCount() * extraNonQualifierSelectivity;
				src = costEstimate.singleScanRowCount() * extraNonQualifierSelectivity;
			}
			if (rc != -1) // changed
			{
				costEstimate.setCost(costEstimate.getEstimatedCost(), rc, src);
				optimizer.trace(Optimizer.COST_INCLUDING_EXTRA_NONQUALIFIER_SELECTIVITY,
								tableNumber, 0, 0.0, costEstimate);
			}
			
		recomputeRowCount:
			if (statisticsForTable && !oneRowResultSetForSomeConglom &&
				(statCompositeSelectivity != 1.0d))
			{
				/* if we have statistics we should use statistics to calculate 
				   row  count-- if it has been determined that this table 
				   returns one row for some conglomerate then there is no need 
				   to do this recalculation
				*/

				double compositeStatRC = initialRowCount * statCompositeSelectivity;
				optimizer.trace(Optimizer.COMPOSITE_SEL_FROM_STATS,
								0, 0, statCompositeSelectivity, null);


				if (tableUniquenessFactor > 0.0)
				{
					/* If the row count from the composite statistics
					   comes up more than what the table uniqueness 
					   factor indicates then lets stick with the current
					   row count.
					*/
					if (compositeStatRC > (baseRowCount() *
										   tableUniquenessFactor))
						
					{
						
						break recomputeRowCount;
					}
				}
				
				/* set the row count and the single scan row count
				   to the initialRowCount. initialRowCount is the product
				   of the RC from store * RC of the outerCost.
				   Thus RC = initialRowCount * the selectivity from stats.
				   SingleRC = RC / outerCost.rowCount().
				*/
				costEstimate.setCost(costEstimate.getEstimatedCost(),
									 compositeStatRC,
									 (existsBaseTable()) ? 
									 1 : 
									 compositeStatRC / outerCost.rowCount());
				
				optimizer.trace(Optimizer.COST_INCLUDING_COMPOSITE_SEL_FROM_STATS,
								tableNumber, 0, 0.0, costEstimate);
			}
		}

		/* Put the base predicates back in the predicate list */
		currentJoinStrategy.putBasePredicates(predList,
									   baseTableRestrictionList);
		return costEstimate;
	}
//GemStone changes BEGIN
  private int matchGroupByColumns(ConglomerateDescriptor cd) throws StandardException {
    final int gblSize;

    int matchedGroupingColumns = 0;
    if (this.gbl != null &&
        (gblSize = this.gbl.size()) > 0 && cd.isIndex()) {
      boolean isInSortedOrder = true;
      ColumnReference[] crs = new ColumnReference[gblSize];

      // Now populate the CR array and see if ordered
      int index;
      for (index = 0; index < gblSize; index++) {
        GroupByColumn gc = (GroupByColumn)this.gbl
            .elementAt(index);
        if (gc.getColumnExpression() instanceof ColumnReference) {
          crs[index] = (ColumnReference)gc.getColumnExpression();
        }
        else {
          isInSortedOrder = false;
          break;
        }
      }
      if (isInSortedOrder) {
        isInSortedOrder = isOrdered(crs, cd);
        if (isInSortedOrder) {
          // assume all group by columns matched; for the
          // special case of unique index checked in
          // "isOrdered" it still does not hurt for costing
          matchedGroupingColumns = gblSize;
        }
      }
    }
    return matchedGroupingColumns;
  }
//GemStone changes END	
	//GemStone changes BEGIN
	private boolean indexCoverSort(ConglomerateDescriptor cd, Optimizer optimizer) 
	    throws StandardException {
          boolean indexCoverSort = true;
          // check if Index takes care of Order BY and is in the same order as column
          // order of ConglomerateDescriptor
	  RequiredRowOrdering  requiredRowOrdering = ((OptimizerImpl)optimizer).requiredRowOrdering;
          OrderedColumnList  orderedList = (OrderedColumnList)requiredRowOrdering; 
          int orderByListSize = orderedList == null? 0 : orderedList.size();
          if (orderByListSize > 0) {
            ColumnReference[] crs = new ColumnReference[orderByListSize];

            // Now populate the CR array and see if ordered
            int index;
            for (index = 0; index < orderByListSize; index++) {
              OrderedColumn orderedColumn = orderedList.getOrderedColumn(index);
              ResultColumn rc = orderedColumn.getResultColumn();
              if (rc != null && rc.getExpression() instanceof ColumnReference) {
                crs[index] = (ColumnReference)rc.getExpression();
              }
              else {
                indexCoverSort = false;
                break;
              }
            }
            
            if (indexCoverSort) {
              indexCoverSort = isOrdered(crs, cd);
            }
          }
          else {
            indexCoverSort = false;
          }
          
          return indexCoverSort;
	}
	
	private boolean existsBaseTable() {
	  return GemFireXDUtils.isSet(this.flags, existsBaseTable);
	}
	
	private boolean getUpdateLocks() {
          return GemFireXDUtils.isSet(this.flags, getUpdateLocks);
        }
	
	private boolean bulkFetchTurnedOff() {
	  return GemFireXDUtils.isSet(this.flags, bulkFetchTurnedOff);
	}
	
	private boolean skipIndexRowToBaseRow() {
          return GemFireXDUtils.isSet(this.flags, skipIndexRowToBaseRow);
        }
	
	private boolean specialMaxScan() {
          return GemFireXDUtils.isSet(this.flags, specialMaxScan);
        }
	
	private boolean specialRegionSize() {
          return GemFireXDUtils.isSet(this.flags, specialRegionSize);
        }
	
	private boolean distinctScan() {
          return GemFireXDUtils.isSet(this.flags, distinctScan);
        }
	
	private boolean raDependentScan() {
          return GemFireXDUtils.isSet(this.flags, raDependentScan);
        }
	
	private boolean createGFEResultSet() {
          return GemFireXDUtils.isSet(this.flags, createGFEResultSet);
        }
	
	private boolean multiProbing() {
          return GemFireXDUtils.isSet(this.flags, multiProbing);
        }
	
	private boolean delayScanOpening() {
          return GemFireXDUtils.isSet(this.flags, delayScanOpening);
        }
	
	
	
	private void setFlagMask(int MASK, boolean on) {
	  this.flags = GemFireXDUtils.set(this.flags, MASK, on);
	}
	
	private boolean isOptimizeForOffHeap() {
    return GemFireXDUtils.isSet(this.flags, optimizeForOffHeap);
  }
	
	private boolean indexAccesesBaseTable() {
    return GemFireXDUtils.isSet(this.flags, indexAccesesBaseTable);
  }

	//GemStone changes END
	private double scanCostAfterSelectivity(double originalScanCost,
											double initialPositionCost,
											double selectivity,
											boolean anotherIndexUnique)
			throws StandardException
	{
		/* If there's another paln using unique index, its selectivity is 1/r
		 * because we use row count 1.  This plan is not unique index, so we make
		 * selectivity at least 2/r, which is more fair, because for unique index
		 * we don't use our selectivity estimates.  Unique index also more likely
		 * locks less rows, hence better concurrency.  beetle 4787.
		 */
		if (anotherIndexUnique)
		{
			double r = baseRowCount();
			if (r > 0.0)
			{
				double minSelectivity = 2.0 / r;
				if (minSelectivity > selectivity)
					selectivity = minSelectivity;
			}
		}
		
		/* initialPositionCost is the first part of the index scan cost we get above.
		 * It's the cost of initial positioning/fetch of key.  So it's unrelated to
		 * row count of how many rows we fetch from index.  We extract it here so that
		 * we only multiply selectivity to the other part of index scan cost, which is
		 * nearly linear, to make cost calculation more accurate and fair, especially
		 * compared to the plan of "one row result set" (unique index). beetle 4787.
		 */
		double afterInitialCost = (originalScanCost - initialPositionCost) *
				 				selectivity;
		if (afterInitialCost < 0)
			afterInitialCost = 0;
		return initialPositionCost + afterInitialCost;
	}

	private void setLockingBasedOnThreshold(
					Optimizer optimizer, double rowsTouched)
	{
		/* In optimizer we always set it to row lock (unless there's no
		 * start/stop key found to utilize an index, in which case we do table
		 * lock), it's up to store to upgrade it to table lock.  This makes
		 * sense for the default read committed isolation level and update
		 * lock.  For more detail, see Beetle 4133.
		 */
		getCurrentAccessPath().setLockMode(
									TransactionController.MODE_RECORD);
	}

	/** @see Optimizable#isBaseTable */
	@Override
  public boolean isBaseTable()
	{
		return true;
	}

	/** @see Optimizable#forUpdate */
	@Override
  public boolean forUpdate()
	{
		/* This table is updatable if it is the
		 * target table of an update or delete,
		 * or it is (or was) the target table of an
		 * updatable cursor.
		 */
		return (updateOrDelete != 0) || cursorTargetTable || getUpdateLocks();
	}

	/** @see Optimizable#initialCapacity */
	@Override
  public int initialCapacity()
	{
		return initialCapacity;
	}

	/** @see Optimizable#loadFactor */
	@Override
  public float loadFactor()
	{
		return loadFactor;
	}

	/**
	 * @see Optimizable#memoryUsageOK
	 */
	@Override
  public boolean memoryUsageOK(double rowCount, int maxMemoryPerTable)
			throws StandardException
	{
		return super.memoryUsageOK(singleScanRowCount, maxMemoryPerTable);
	}

	/**
	 * @see Optimizable#isTargetTable
	 */
	@Override
  public boolean isTargetTable()
	{
		return (updateOrDelete != 0);
	}

	/**
	 * @see Optimizable#uniqueJoin
	 */
	@Override
  public double uniqueJoin(OptimizablePredicateList predList)
					throws StandardException
	{
		double retval = -1.0;
		PredicateList pl = (PredicateList) predList;
		int numColumns = getTableDescriptor().getNumberOfColumns();
		int tableNumber = getTableNumber();

		// This is supposed to be an array of table numbers for the current
		// query block. It is used to determine whether a join is with a
		// correlation column, to fill in eqOuterCols properly. We don't care
		// about eqOuterCols, so just create a zero-length array, pretending
		// that all columns are correlation columns.
		int[] tableNumbers = new int[0];
		JBitSet[] tableColMap = new JBitSet[1];
		tableColMap[0] = new JBitSet(numColumns + 1);

		pl.checkTopPredicatesForEqualsConditions(tableNumber,
												null,
												tableNumbers,
												tableColMap,
												false);

		if (supersetOfUniqueIndex(tableColMap))
		{
			retval =
				getBestAccessPath().getCostEstimate().singleScanRowCount();
		}

		return retval;
	}

	/**
	 * @see Optimizable#isOneRowScan
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean isOneRowScan() 
		throws StandardException
	{
		/* EXISTS FBT will never be a 1 row scan.
		 * Otherwise call method in super class.
		 */
		if (existsBaseTable())
		{
			return false;
		}
		

		return super.isOneRowScan();
	}

	/**
	 * @see Optimizable#legalJoinOrder
	 */
	@Override
  public boolean legalJoinOrder(JBitSet assignedTableMap)
	{
		// Only an issue for EXISTS FBTs
	  
	        // GemStone changes BEGIN
	        // Also an issue for NCJ
                CompilerContext cc = getCompilerContext();
                if ((cc.isNCJoinOnRemote() && assignedTableMap != null && this.dependencyMap != null)
                    ||
		// GemStone changes END
		    existsBaseTable())
		{
			/* Have all of our dependencies been satisfied? */
			return assignedTableMap.contains(dependencyMap);
		}
		return true;
	}

	/**
	 * Convert this object to a String.  See comments in QueryTreeNode.java
	 * for how this should be done for tree printing.
	 *
	 * @return	This object as a String
	 */

	@Override
  public String toString()
	{
		if (SanityManager.DEBUG)
		{
			return "tableName: " +
				(tableName != null ? tableName.toString() : "null") + "\n" +
				"tableDescriptor: " + tableDescriptor + "\n" +
				"correlationName: " + correlationName + "\n" +
				"updateOrDelete: " + updateOrDelete + "\n" +
				(tableProperties != null ?
					tableProperties.toString() : "null") + "\n" +
				"existsBaseTable: " + existsBaseTable + "\n" +
				"dependencyMap: " +
				(dependencyMap != null 
						? dependencyMap.toString() 
						: "null") + "\n" +
				super.toString();
		}
		else
		{
			return "";
		}
	}

	/**
	 * Does this FBT represent an EXISTS FBT.
	 *
	 * @return Whether or not this FBT represents
	 *			an EXISTS FBT.
	 */
	boolean getExistsBaseTable()
	{
		return existsBaseTable();
	}

	/**
	 * Set whether or not this FBT represents an
	 * EXISTS FBT.
	 *
 	 * @param existsBaseTable Whether or not an EXISTS FBT.
	 * @param dependencyMap	  The dependency map for the EXISTS FBT.
 	 * @param isNotExists     Whether or not for NOT EXISTS, more specifically.
	 */
	void setExistsBaseTable(boolean existsBaseTable, JBitSet dependencyMap, boolean isNotExists)
	{
	        //GemStone changes BEGIN
		//this.existsBaseTable = existsBaseTable;
	        this.setFlagMask(FromBaseTable.existsBaseTable, existsBaseTable);
	        this.setFlagMask(FromBaseTable.isNotExists, isNotExists);
		//this.isNotExists = isNotExists;

		//GemStone changes END
		/* Set/clear the dependency map as needed */
		if (existsBaseTable)
		{
			this.dependencyMap = dependencyMap;
		}
		else
		{
			this.dependencyMap = null;
		}
	}

	/**
	 * Clear the bits from the dependency map when join nodes are flattened
	 *
	 * @param locations	vector of bit numbers to be cleared
	 */
	void clearDependency(Vector locations)
	{
		if (this.dependencyMap != null)
		{
			for (int i = 0; i < locations.size() ; i++)
				this.dependencyMap.clear(((Integer)locations.elementAt(i)).intValue());
		}
	}
	
        /**
         * NCJ Note: Only to be used for NCJ. Optimizer verifies dependency of every
         * join order it considers with this dependency map of each table.
         * 
         * @see 
         *      com.pivotal.gemfirexd.internal.impl.sql.compile.FromList.flattenFromTables
         *      (ResultColumnList, PredicateList, SubqueryList, GroupByList)
         */
        public void setDependency(int tableNum, int numTables) {
          if (this.dependencyMap == null || this.dependencyMap.size() != numTables) {
            this.dependencyMap = new JBitSet(numTables);
          }
          this.dependencyMap.set(tableNum);
        }

	/**
	 * Set the table properties for this table.
	 *
	 * @param tableProperties	The new table properties.
	 */
	public void setTableProperties(Properties tableProperties)
	{
		this.tableProperties = tableProperties;
	}

	/**
	 * Bind the table in this FromBaseTable.
	 * This is where view resolution occurs
	 *
	 * @param dataDictionary	The DataDictionary to use for binding
	 * @param fromListParam		FromList to use/append to.
	 *
	 * @return	ResultSetNode	The FromTable for the table or resolved view.
	 *
	 * @exception StandardException		Thrown on error
	 */

	@Override
  public ResultSetNode bindNonVTITables(DataDictionary dataDictionary, 
						   FromList fromListParam) 
					throws StandardException
	{
		TableDescriptor tableDescriptor = bindTableDescriptor();

		if (tableDescriptor.getTableType() == TableDescriptor.VTI_TYPE) {
			ResultSetNode vtiNode = getNodeFactory().mapTableAsVTI(
					tableDescriptor,
					getCorrelationName(),
					resultColumns,
					getProperties(),
					getContextManager());
			return vtiNode.bindNonVTITables(dataDictionary, fromListParam);
		}	
		
		ResultColumnList	derivedRCL = resultColumns;
  
		// make sure there's a restriction list
		restrictionList = (PredicateList) getNodeFactory().getNode(
											C_NodeTypes.PREDICATE_LIST,
											getContextManager());
		baseTableRestrictionList = (PredicateList) getNodeFactory().getNode(
											C_NodeTypes.PREDICATE_LIST,
											getContextManager());


		CompilerContext compilerContext = getCompilerContext();

		/* Generate the ResultColumnList */
		resultColumns = genResultColList();
		templateColumns = resultColumns;

		/* Resolve the view, if this is a view */
		if (tableDescriptor.getTableType() == TableDescriptor.VIEW_TYPE)
		{
			FromTable					fsq;
			ResultSetNode				rsn;
			ViewDescriptor				vd;
			CreateViewNode				cvn;
			SchemaDescriptor			compSchema;
			SchemaDescriptor			prevCompSchema;

			/* Get the associated ViewDescriptor so that we can get 
			 * the view definition text.
			 */
			vd = dataDictionary.getViewDescriptor(tableDescriptor);

			/*
			** Set the default compilation schema to be whatever
			** this schema this view was originally compiled against.
			** That way we pick up the same tables no matter what
			** schema we are running against.
			*/
			compSchema = dataDictionary.getSchemaDescriptor(vd.getCompSchemaId(), null);

			prevCompSchema = compilerContext.setCompilationSchema(compSchema);
	
			try
			{
		
				/* This represents a view - query is dependent on the ViewDescriptor */
				compilerContext.createDependency(vd);
	
				if (SanityManager.DEBUG)
				{
					SanityManager.ASSERT(vd != null,
						"vd not expected to be null for " + tableName);
				}
	
				cvn = (CreateViewNode)
				          parseStatement(vd.getViewText(), false);

				rsn = cvn.getParsedQueryExpression();
	
				/* If the view contains a '*' then we mark the views derived column list
				 * so that the view will still work, and return the expected results,
				 * if any of the tables referenced in the view have columns added to
				 * them via ALTER TABLE.  The expected results means that the view
				 * will always return the same # of columns.
				 */
				if (rsn.getResultColumns().containsAllResultColumn())
				{
					resultColumns.setCountMismatchAllowed(true);
				}
				//Views execute with definer's privileges and if any one of 
				//those privileges' are revoked from the definer, the view gets
				//dropped. So, a view can exist in Derby only if it's owner has
				//all the privileges needed to create one. In order to do a 
				//select from a view, a user only needs select privilege on the
				//view and doesn't need any privilege for objects accessed by
				//the view. Hence, when collecting privilege requirement for a
				//sql accessing a view, we only need to look for select privilege
				//on the actual view and that is what the following code is
				//checking.
				for (int i = 0; i < resultColumns.size(); i++) {
					ResultColumn rc = (ResultColumn) resultColumns.elementAt(i);
					if (rc.isPrivilegeCollectionRequired())
						compilerContext.addRequiredColumnPriv( rc.getTableColumnDescriptor());
				}

				fsq = (FromTable) getNodeFactory().getNode(
					C_NodeTypes.FROM_SUBQUERY,
					rsn, 
					(correlationName != null) ? 
                        correlationName : getOrigTableName().getTableName(), 
					resultColumns,
					tableProperties,
					getContextManager());
				// Transfer the nesting level to the new FromSubquery
				fsq.setLevel(level);
				//We are getting ready to bind the query underneath the view. Since
				//that query is going to run with definer's privileges, we do not
				//need to collect any privilege requirement for that query. 
				//Following call is marking the query to run with definer 
				//privileges. This marking will make sure that we do not collect
				//any privilege requirement for it.
				fsq.disablePrivilegeCollection();
				fsq.setOrigTableName(this.getOrigTableName());
				return fsq.bindNonVTITables(dataDictionary, fromListParam);
			}
			finally
			{
				compilerContext.setCompilationSchema(prevCompSchema);
			}
		}
		else
		{
			if ( ((GenericStatement)compilerContext.getParentPS().getStatement()).getRouteQuery() &&
					tableDescriptor.getRowLevelSecurityEnabledFlag()) {
				throw StandardException.newException(SQLState.ROW_LEVEL_SECURITY_ENABLED);
			}
			/* This represents a table - query is dependent on the TableDescriptor */
			compilerContext.createDependency(tableDescriptor);

			/* Get the base conglomerate descriptor */
			baseConglomerateDescriptor =
				tableDescriptor.getConglomerateDescriptor(
					tableDescriptor.getHeapConglomerateId()
					);

			/* Build the 0-based array of base column names. */
			columnNames = resultColumns.getColumnNames();

			/* Do error checking on derived column list and update "exposed"
			 * column names if valid.
			 */
			if (derivedRCL != null)
			{
				 resultColumns.propagateDCLInfo(derivedRCL, 
											    origTableName.getFullTableName());
			}

			/* Assign the tableNumber */
			if (tableNumber == -1)  // allow re-bind, in which case use old number
				tableNumber = compilerContext.getNextTableNumber();
		}

		return this;
	}

	/** 
	 * Determine whether or not the specified name is an exposed name in
	 * the current query block.
	 *
	 * @param name	The specified name to search for as an exposed name.
	 * @param schemaName	Schema name, if non-null.
	 * @param exactMatch	Whether or not we need an exact match on specified schema and table
	 *						names or match on table id.
	 *
	 * @return The FromTable, if any, with the exposed name.
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  protected FromTable getFromTableByName(String name, String schemaName, boolean exactMatch)
		throws StandardException
	{
		// ourSchemaName can be null if correlation name is specified.
		String ourSchemaName = getOrigTableName().getSchemaName();
		String fullName = (schemaName != null) ? (schemaName + '.' + name) : name;

		/* If an exact string match is required then:
		 *	o  If schema name specified on 1 but not both then no match.
		 *  o  If schema name not specified on either, compare exposed names.
		 *  o  If schema name specified on both, compare schema and exposed names.
		 */
		if (exactMatch)
		{

			if ((schemaName != null && ourSchemaName == null) ||
				(schemaName == null && ourSchemaName != null))
			{
				return null;
			}

			if (getExposedName().equals(fullName))
			{
				return this;
			}

			return null;
		}

		/* If an exact string match is not required then:
		 *  o  If schema name specified on both, compare schema and exposed names.
		 *  o  If schema name not specified on either, compare exposed names.
		 *	o  If schema name specified on column but not table, then compare
		 *	   the column's schema name against the schema name from the TableDescriptor.
		 *	   If they agree, then the column's table name must match the exposed name
		 *	   from the table, which must also be the base table name, since a correlation
		 *	   name does not belong to a schema.
		 *  o  If schema name not specified on column then just match the exposed names.
		 */
		// Both or neither schema name specified
		if (getExposedName().equals(fullName))
		{
			return this;
		}
		else if ((schemaName != null && ourSchemaName != null) ||
				 (schemaName == null && ourSchemaName == null))
		{
			return null;
		}

		// Schema name only on column
		// e.g.:  select w1.i from t1 w1 order by test2.w1.i;  (incorrect)
		if (schemaName != null && ourSchemaName == null)
		{
			// Compare column's schema name with table descriptor's if it is
			// not a synonym since a synonym can be declared in a different
			// schema.
			if (tableName.equals(origTableName) && 
					! schemaName.equals(tableDescriptor.getSchemaDescriptor().getSchemaName()))
			{
				return null;
			}

			// Compare exposed name with column's table name
			if (! getExposedName().equals(name))
			{
				return null;
			}

			// Make sure exposed name is not a correlation name
			if (! getExposedName().equals(getOrigTableName().getTableName()))
			{
				return null;
			}

			return this;
		}

		/* Schema name only specified on table. Compare full exposed name
		 * against table's schema name || "." || column's table name.
		 */
		if (! getExposedName().equals(getOrigTableName().getSchemaName() + "." + name))
		{
			return null;
		}

		return this;
	}


	/**
	  *	Bind the table descriptor for this table.
	  *
	  * If the tableName is a synonym, it will be resolved here.
	  * The original table name is retained in origTableName.
	  * 
	  * @exception StandardException		Thrown on error
	  */
	private	TableDescriptor	bindTableDescriptor()
		throws StandardException
	{
		String schemaName = tableName.getSchemaName();
		SchemaDescriptor sd = getSchemaDescriptor(schemaName);

		tableDescriptor = getTableDescriptor(tableName.getTableName(), sd);
		if (tableDescriptor == null)
		{
			// Check if the reference is for a synonym.
			TableName synonymTab = resolveTableToSynonym(tableName);
			if (synonymTab == null)
				throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND, tableName.getFullTableName());
			
			tableName = synonymTab;
			sd = getSchemaDescriptor(tableName.getSchemaName());

			tableDescriptor = getTableDescriptor(synonymTab.getTableName(), sd);
			if (tableDescriptor == null)
				throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND, tableName.getFullTableName());
		}

		return	tableDescriptor;
	}


	/**
	 * Bind the expressions in this FromBaseTable.  This means binding the
	 * sub-expressions, as well as figuring out what the return type is for
	 * each expression.
	 *
	 * @param fromListParam		FromList to use/append to.
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public void bindExpressions(FromList fromListParam)
					throws StandardException
	{
		/* No expressions to bind for a FromBaseTable.
		 * NOTE - too involved to optimize so that this method
		 * doesn't get called, so just do nothing.
		 */
	}

	/**
	 * Bind the result columns of this ResultSetNode when there is no
	 * base table to bind them to.  This is useful for SELECT statements,
	 * where the result columns get their types from the expressions that
	 * live under them.
	 *
	 * @param fromListParam		FromList to use/append to.
	 *
	 * @exception StandardException		Thrown on error
	 */

	@Override
  public void bindResultColumns(FromList fromListParam)
				throws StandardException
	{
		/* Nothing to do, since RCL bound in bindNonVTITables() */
	}

	/**
	 * Try to find a ResultColumn in the table represented by this FromBaseTable
	 * that matches the name in the given ColumnReference.
	 *
	 * @param columnReference	The columnReference whose name we're looking
	 *				for in the given table.
	 *
	 * @return	A ResultColumn whose expression is the ColumnNode
	 *			that matches the ColumnReference.
	 *		Returns null if there is no match.
	 *
	 * @exception StandardException		Thrown on error
	 */

	@Override
  public ResultColumn getMatchingColumn(ColumnReference columnReference) throws StandardException
	{
		ResultColumn	resultColumn = null;
		TableName		columnsTableName;
		TableName		exposedTableName;

		columnsTableName = columnReference.getTableNameNode();

        if(columnsTableName != null) {
            if(columnsTableName.getSchemaName() == null && correlationName == null)
                columnsTableName.bind(this.getDataDictionary());
        }
		/*
		** If there is a correlation name, use that instead of the
		** table name.
		*/
        exposedTableName = getExposedTableName();

        if(exposedTableName.getSchemaName() == null && correlationName == null)
            exposedTableName.bind(this.getDataDictionary());
		/*
		** If the column did not specify a name, or the specified name
		** matches the table we're looking at, see whether the column
		** is in this table.
		*/
		if (columnsTableName == null || columnsTableName.equals(exposedTableName))
		{
			resultColumn = resultColumns.getResultColumn(columnReference.getColumnName());
			/* Did we find a match? */
			if (resultColumn != null)
			{
				columnReference.setTableNumber(tableNumber);
				if (tableDescriptor != null)
				{
					FormatableBitSet referencedColumnMap = tableDescriptor.getReferencedColumnMap();
					if (referencedColumnMap == null)
						referencedColumnMap = new FormatableBitSet(
									tableDescriptor.getNumberOfColumns() + 1);
					referencedColumnMap.set(resultColumn.getColumnPosition());
					tableDescriptor.setReferencedColumnMap(referencedColumnMap);
				}
			}
		}

		return resultColumn;
	}

	/**
	 * Preprocess a ResultSetNode - this currently means:
	 *	o  Generating a referenced table map for each ResultSetNode.
	 *  o  Putting the WHERE and HAVING clauses in conjunctive normal form (CNF).
	 *  o  Converting the WHERE and HAVING clauses into PredicateLists and
	 *	   classifying them.
	 *  o  Ensuring that a ProjectRestrictNode is generated on top of every 
	 *     FromBaseTable and generated in place of every FromSubquery.  
	 *  o  Pushing single table predicates down to the new ProjectRestrictNodes.
	 *
	 * @param numTables			The number of tables in the DML Statement
	 * @param gbl				The group by list, if any
	 * @param fromList			The from list, if any
	 *
	 * @return ResultSetNode at top of preprocessed tree.
	 *
	 * @exception StandardException		Thrown on error
	 */

	@Override
  public ResultSetNode preprocess(int numTables,
									GroupByList gbl,
									FromList fromList)
								throws StandardException
	{
		/* Generate the referenced table map */
		referencedTableMap = new JBitSet(numTables);
		referencedTableMap.set(tableNumber);

// GemStone changes BEGIN
		this.gbl = gbl;
// GemStone changes END
		return genProjectRestrict(numTables);
	}

	/** 
	 * Put a ProjectRestrictNode on top of each FromTable in the FromList.
	 * ColumnReferences must continue to point to the same ResultColumn, so
	 * that ResultColumn must percolate up to the new PRN.  However,
	 * that ResultColumn will point to a new expression, a VirtualColumnNode, 
	 * which points to the FromTable and the ResultColumn that is the source for
	 * the ColumnReference.  
	 * (The new PRN will have the original of the ResultColumnList and
	 * the ResultColumns from that list.  The FromTable will get shallow copies
	 * of the ResultColumnList and its ResultColumns.  ResultColumn.expression
	 * will remain at the FromTable, with the PRN getting a new 
	 * VirtualColumnNode for each ResultColumn.expression.)
	 * We then project out the non-referenced columns.  If there are no referenced
	 * columns, then the PRN's ResultColumnList will consist of a single ResultColumn
	 * whose expression is 1.
	 *
	 * @param numTables			Number of tables in the DML Statement
	 *
	 * @return The generated ProjectRestrictNode atop the original FromTable.
	 *
	 * @exception StandardException		Thrown on error
	 */

	@Override
  protected ResultSetNode genProjectRestrict(int numTables)
				throws StandardException
	{
		/* We get a shallow copy of the ResultColumnList and its 
		 * ResultColumns.  (Copy maintains ResultColumn.expression for now.)
		 */
		ResultColumnList prRCList = resultColumns;
		resultColumns = resultColumns.copyListAndObjects();

		/* Replace ResultColumn.expression with new VirtualColumnNodes
		 * in the ProjectRestrictNode's ResultColumnList.  (VirtualColumnNodes include
		 * pointers to source ResultSetNode, this, and source ResultColumn.)
		 * NOTE: We don't want to mark the underlying RCs as referenced, otherwise
		 * we won't be able to project out any of them.
		 */
		prRCList.genVirtualColumnNodes(this, resultColumns, false);

		/* Project out any unreferenced columns.  If there are no referenced 
		 * columns, generate and bind a single ResultColumn whose expression is 1.
		 */
		prRCList.doProjection();

		/* Finally, we create the new ProjectRestrictNode */
		return (ResultSetNode) getNodeFactory().getNode(
								C_NodeTypes.PROJECT_RESTRICT_NODE,
								this,
								prRCList,
								null,	/* Restriction */
								null,   /* Restriction as PredicateList */
								null,	/* Project subquery list */
								null,	/* Restrict subquery list */
								null,
								getContextManager()				 );
	}

	/**
	 * @see ResultSetNode#changeAccessPath
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public ResultSetNode changeAccessPath() throws StandardException
	{
		ResultSetNode	retval;
		AccessPath ap = getTrulyTheBestAccessPath();
		ConglomerateDescriptor trulyTheBestConglomerateDescriptor = 
							 					ap.getConglomerateDescriptor();
		JoinStrategy trulyTheBestJoinStrategy = ap.getJoinStrategy();
		Optimizer optimizer = ap.getOptimizer();

		optimizer.trace(Optimizer.CHANGING_ACCESS_PATH_FOR_TABLE,
						tableNumber, 0, 0.0, null);

		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(
				trulyTheBestConglomerateDescriptor != null,
				"Should only modify access path after conglomerate has been chosen.");
		}

		/*
		** Make sure user-specified bulk fetch is OK with the chosen join
		** strategy.
		*/
		if (bulkFetch != UNSET)
		{
			if ( ! trulyTheBestJoinStrategy.bulkFetchOK())
			{
				throw StandardException.newException(SQLState.LANG_INVALID_BULK_FETCH_WITH_JOIN_TYPE, 
											trulyTheBestJoinStrategy.getName());
			}
			// bulkFetch has no meaning for hash join, just ignore it
			else if (trulyTheBestJoinStrategy.ignoreBulkFetch())
			{
				disableBulkFetch();
			}
			// bug 4431 - ignore bulkfetch property if it's 1 row resultset
			else if (isOneRowResultSet())
			{
				disableBulkFetch();
			}
		}

		// bulkFetch = 1 is the same as no bulk fetch
		if (bulkFetch == 1)
		{
			disableBulkFetch();
		}

		/* Remove any redundant join clauses.  A redundant join clause is one
		 * where there are other join clauses in the same equivalence class
		 * after it in the PredicateList.
		 */
// GemStone changes BEGIN
		final boolean hasRestrictionList = (restrictionList != null);
		final boolean hasOrList = hasRestrictionList &&
		    this.restrictionList.hasOrList();
		if (hasRestrictionList)
// GemStone changes END
		restrictionList.removeRedundantPredicates();

		/*
		** Divide up the predicates for different processing phases of the
		** best join strategy.
		*/
		storeRestrictionList = (PredicateList) getNodeFactory().getNode(
													C_NodeTypes.PREDICATE_LIST,
													getContextManager());
		nonStoreRestrictionList = (PredicateList) getNodeFactory().getNode(
													C_NodeTypes.PREDICATE_LIST,
													getContextManager());
		requalificationRestrictionList =
									(PredicateList) getNodeFactory().getNode(
													C_NodeTypes.PREDICATE_LIST,
													getContextManager());
// GemStone changes BEGIN
		if (hasRestrictionList)
// GemStone changes END
		trulyTheBestJoinStrategy.divideUpPredicateLists(
											this,
											restrictionList,
											storeRestrictionList,
											nonStoreRestrictionList,
											requalificationRestrictionList,
											getDataDictionary());

// GemStone changes BEGIN
		final CompilerContext cc = getCompilerContext();
		if (hasRestrictionList)
// GemStone changes END
		/* Check to see if we are going to do execution-time probing
		 * of an index using IN-list values.  We can tell by looking
		 * at the restriction list: if there is an IN-list probe
		 * predicate that is also a start/stop key then we know that
		 * we're going to do execution-time probing.  In that case
		 * we disable bulk fetching to minimize the number of non-
		 * matching rows that we read from disk.  RESOLVE: Do we
		 * really need to completely disable bulk fetching here,
		 * or can we do something else?
		 */
		for (int i = 0; i < restrictionList.size(); i++)
		{
			Predicate pred = (Predicate)restrictionList.elementAt(i);
			if (pred.isInListProbePredicate() && pred.isStartKey())
			{
				disableBulkFetch();
				//GemStone changes BEGIN
				//multiProbing = true;
				this.setFlagMask(multiProbing, true);
				//GemStone changes END
				break;
			}
		}

		/*
		** Consider turning on bulkFetch if it is turned
		** off.  Only turn it on if it is a not an updatable
		** scan and if it isn't a oneRowResultSet, and
		** not a subquery, and it is OK to use bulk fetch
		** with the chosen join strategy.  NOTE: the subquery logic
		** could be more sophisticated -- we are taking
		** the safe route in avoiding reading extra
		** data for something like:
		**
		**	select x from t where x in (select y from t)
	 	**
		** In this case we want to stop the subquery
		** evaluation as soon as something matches.
		*/
		if (trulyTheBestJoinStrategy.bulkFetchOK() &&
			!(trulyTheBestJoinStrategy.ignoreBulkFetch()) &&
			! bulkFetchTurnedOff() &&
			(bulkFetch == UNSET) && 
			!forUpdate() && 
			!isOneRowResultSet() &&
			getLevel() == 0)
		{
			bulkFetch = getDefaultBulkFetch();	
		}

		/* Statement is dependent on the chosen conglomerate. */
// GemStone changes BEGIN
		cc.createDependency(
		/* (original code)
		getCompilerContext().createDependency(
		*/
// GemStone changes END
				trulyTheBestConglomerateDescriptor);

		/* No need to modify access path if conglomerate is the heap */
// GemStone changes BEGIN
		if ( ! trulyTheBestConglomerateDescriptor.isIndex() || hasOrList)
		/* (original code)
		if ( ! trulyTheBestConglomerateDescriptor.isIndex())
		*/
// GemStone changes END
		{
			/*
			** We need a little special logic for SYSSTATEMENTS
			** here.  SYSSTATEMENTS has a hidden column at the
			** end.  When someone does a select * we don't want
			** to get that column from the store.  So we'll always
			** generate a partial read bitSet if we are scanning
			** SYSSTATEMENTS to ensure we don't get the hidden
			** column.
			*/
			boolean isSysstatements = tableName.equals("SYS","SYSSTATEMENTS");
			/* Template must reflect full row.
			 * Compact RCL down to partial row.
			 */
			templateColumns = resultColumns;
			referencedCols = resultColumns.getReferencedFormatableBitSet(cursorTargetTable, isSysstatements, false);
			resultColumns = resultColumns.compactColumns(cursorTargetTable, isSysstatements);
			return this;
		}
		
		/* No need to go to the data page if this is a covering index */
		/* Derby-1087: use data page when returning an updatable resultset */
		if (ap.getCoveringIndexScan() && (!cursorTargetTable()))
		{
			/* Massage resultColumns so that it matches the index. */
			resultColumns = newResultColumns(resultColumns,
				 							 trulyTheBestConglomerateDescriptor,
											 baseConglomerateDescriptor,
											 false);

			/* We are going against the index.  The template row must be the full index row.
			 * The template row will have the RID but the result row will not
			 * since there is no need to go to the data page.
			 */
			templateColumns = newResultColumns(resultColumns,
				 							 trulyTheBestConglomerateDescriptor,
											 baseConglomerateDescriptor,
											 false);
			templateColumns.addRCForRID();

			// If this is for update then we need to get the RID in the result row
			if (forUpdate())
			{
				resultColumns.addRCForRID();
			}
			
			/* Compact RCL down to the partial row.  We always want a new
			 * RCL and FormatableBitSet because this is a covering index.  (This is 
			 * because we don't want the RID in the partial row returned
			 * by the store.)
			 */
			referencedCols = resultColumns.getReferencedFormatableBitSet(cursorTargetTable,true, false);
			resultColumns = resultColumns.compactColumns(cursorTargetTable,true);

			resultColumns.setIndexRow(
				baseConglomerateDescriptor.getConglomerateNumber(), 
				forUpdate());

			return this;
		}

		/* Statement is dependent on the base conglomerate if this is 
		 * a non-covering index. 
		 */
// GemStone changes BEGIN
		if (hasRestrictionList) {
		  cc.createDependency(baseConglomerateDescriptor);
		}
		/* (original code)
		getCompilerContext().createDependency(baseConglomerateDescriptor);
		*/
// GemStone changes END

		/*
		** On bulkFetch, we need to add the restrictions from
		** the TableScan and reapply them  here.
		*/
		if (bulkFetch != UNSET)
		{
// GemStone changes BEGIN
			//restrictionList.copyPredicatesToOtherList(
			if (hasRestrictionList)
			restrictionList.copyPredicatesToOtherListSkipPushedPredicates(
 // GemStone changes END
												requalificationRestrictionList);
		}

		/*
		** We know the chosen conglomerate is an index.  We need to allocate
		** an IndexToBaseRowNode above us, and to change the result column
		** list for this FromBaseTable to reflect the columns in the index.
		** We also need to shift "cursor target table" status from this
		** FromBaseTable to the new IndexToBaseRowNow (because that's where
		** a cursor can fetch the current row).
		*/
		ResultColumnList newResultColumns =
			newResultColumns(resultColumns,
							trulyTheBestConglomerateDescriptor,
							baseConglomerateDescriptor,
							true
							);

// GemStone changes BEGIN
		if (!this.skipIndexRowToBaseRow()) {
// GemStone changes END
		/* Compact the RCL for the IndexToBaseRowNode down to
		 * the partial row for the heap.  The referenced BitSet
		 * will reflect only those columns coming from the heap.
		 * (ie, it won't reflect columns coming from the index.)
		 * NOTE: We need to re-get all of the columns from the heap
		 * when doing a bulk fetch because we will be requalifying
		 * the row in the IndexRowToBaseRow.
		 */
		// Get the BitSet for all of the referenced columns
		FormatableBitSet indexReferencedCols = null;
		FormatableBitSet heapReferencedCols = null;
		if ((bulkFetch == UNSET) && 
			(requalificationRestrictionList == null || 
			 requalificationRestrictionList.size() == 0))
		{
			/* No BULK FETCH or requalification, XOR off the columns coming from the heap 
			 * to get the columns coming from the index.
			 */
			indexReferencedCols = resultColumns.getReferencedFormatableBitSet(cursorTargetTable, true, false);
			heapReferencedCols = resultColumns.getReferencedFormatableBitSet(cursorTargetTable, true, true);
			if (heapReferencedCols != null)
			{
				indexReferencedCols.xor(heapReferencedCols);
			}
		}
		else
		{
			// BULK FETCH or requalification - re-get all referenced columns from the heap
			heapReferencedCols = resultColumns.getReferencedFormatableBitSet(cursorTargetTable, true, false) ;
		}
		this.setFlagMask(indexAccesesBaseTable, true);
		ResultColumnList heapRCL = resultColumns.compactColumns(cursorTargetTable, false);
		retval = (ResultSetNode) getNodeFactory().getNode(
										C_NodeTypes.INDEX_TO_BASE_ROW_NODE,
										this,
										baseConglomerateDescriptor,
										heapRCL,
										Boolean.valueOf(cursorTargetTable),
										heapReferencedCols,
										indexReferencedCols,
										requalificationRestrictionList,
										Boolean.valueOf(forUpdate()),
										tableProperties,
										getContextManager());
		
// GemStone changes BEGIN
		 ((IndexToBaseRowNode)retval).delayScanOpening(this.delayScanOpening());
		
		}
		else {
		  retval = this;
		}
// GemStone changes END


		/*
		** The template row is all the columns.  The
		** result set is the compacted column list.
		*/
		resultColumns = newResultColumns;

		templateColumns = newResultColumns(resultColumns,
			 							   trulyTheBestConglomerateDescriptor,
										   baseConglomerateDescriptor,
										   false);
		/* Since we are doing a non-covered index scan, if bulkFetch is on, then
		 * the only columns that we need to get are those columns referenced in the start and stop positions
		 * and the qualifiers (and the RID) because we will need to re-get all of the other
		 * columns from the heap anyway.
		 * At this point in time, columns referenced anywhere in the column tree are 
		 * marked as being referenced.  So, we clear all of the references, walk the 
		 * predicate list and remark the columns referenced from there and then add
		 * the RID before compacting the columns.
		 */
// GemStone changes BEGIN
		if (bulkFetch != UNSET && hasRestrictionList)
		/* (original code)
		if (bulkFetch != UNSET)
		*/
// GemStone changes END
		{
			resultColumns.markAllUnreferenced();
			storeRestrictionList.markReferencedColumns();
			if (nonStoreRestrictionList != null)
			{
				nonStoreRestrictionList.markReferencedColumns();
			}
		}
		resultColumns.addRCForRID();
		templateColumns.addRCForRID();

		// Compact the RCL for the index scan down to the partial row.
		referencedCols = resultColumns.getReferencedFormatableBitSet(cursorTargetTable, false, false);
		resultColumns = resultColumns.compactColumns(cursorTargetTable, false);
		resultColumns.setIndexRow(
				baseConglomerateDescriptor.getConglomerateNumber(), 
				forUpdate());

		/* We must remember if this was the cursorTargetTable
 		 * in order to get the right locking on the scan.
		 */
		//GemStone changes BEGIN
		//getUpdateLocks = cursorTargetTable;
		this.setFlagMask(getUpdateLocks, cursorTargetTable);
		//GemStone changes END
		cursorTargetTable = false;

		return retval;
	}

	/**
	 * Create a new ResultColumnList to reflect the columns in the
	 * index described by the given ConglomerateDescriptor.  The columns
	 * in the new ResultColumnList are based on the columns in the given
	 * ResultColumnList, which reflects the columns in the base table.
	 *
	 * @param oldColumns	The original list of columns, which reflects
	 *						the columns in the base table.
	 * @param idxCD			The ConglomerateDescriptor, which describes
	 *						the index that the new ResultColumnList will
	 *						reflect.
	 * @param heapCD		The ConglomerateDescriptor for the base heap
	 * @param cloneRCs		Whether or not to clone the RCs
	 *
	 * @return	A new ResultColumnList that reflects the columns in the index.
	 *
	 * @exception StandardException		Thrown on error
	 */
	private ResultColumnList newResultColumns(
										ResultColumnList oldColumns,
										ConglomerateDescriptor idxCD,
										ConglomerateDescriptor heapCD,
										boolean cloneRCs)
						throws StandardException
	{
		IndexRowGenerator	irg = idxCD.getIndexDescriptor();
		int[]				baseCols = irg.baseColumnPositions();
		ResultColumnList	newCols =
								(ResultColumnList) getNodeFactory().getNode(
												C_NodeTypes.RESULT_COLUMN_LIST,
												getContextManager());

		for (int i = 0; i < baseCols.length; i++)
		{
			int	basePosition = baseCols[i];
			ResultColumn oldCol = oldColumns.getResultColumn(basePosition);
			ResultColumn newCol;

// GemStone changes BEGIN
			// oldCol can be null for the case of OR list where
			// base table columns will already be compacted
			final boolean oldColNull = (oldCol == null);
			if (oldColNull && this.skipIndexRowToBaseRow()) {
			  for (Object colDesc : tableDescriptor
			      .getColumnDescriptorList()) {
			    ColumnDescriptor cd = (ColumnDescriptor)colDesc;
			    if (basePosition == cd.getPosition()) {
			      cd.setTableDescriptor(tableDescriptor);
			      ValueNode vn = (ValueNode)getNodeFactory().getNode(
			          C_NodeTypes.BASE_COLUMN_NODE,
			          cd.getColumnName(),
			          getExposedTableName(),
			          cd.getType(), getContextManager());
			      oldCol = (ResultColumn)getNodeFactory().getNode(
			          C_NodeTypes.RESULT_COLUMN,
			          cd, vn, getContextManager());
			      oldCol.setReferenced();
			      break;
			    }
			  }
			}
			if (oldCol == null) {
			  SanityManager.THROWASSERT("Couldn't find base column "
			      + basePosition + "\n.  RCL is\n" + oldColumns);
                        }
			/* (original code)
			if (SanityManager.DEBUG)
			{
				SanityManager.ASSERT(oldCol != null, 
							"Couldn't find base column "+basePosition+
							"\n.  RCL is\n"+oldColumns);
			}
			*/
// GemStone changes END

			/* If we're cloning the RCs its because we are
			 * building an RCL for the index when doing
			 * a non-covering index scan.  Set the expression
			 * for the old RC to be a VCN pointing to the
			 * new RC.
			 */
			if (cloneRCs)
			{
// GemStone changes BEGIN
				if (oldColNull) {
				  newCol = oldCol;
				}
				else {
				  newCol = oldCol.cloneMe();
				}
				/* (original code)
				newCol = oldCol.cloneMe();
				*/
// GemStone changes END
				oldCol.setExpression(
					(ValueNode) getNodeFactory().getNode(
						C_NodeTypes.VIRTUAL_COLUMN_NODE,
						this,
						newCol,
						ReuseFactory.getInteger(oldCol.getVirtualColumnId()),
						getContextManager()));
			}
			else
			{
				newCol = oldCol;
			}

			newCols.addResultColumn(newCol);
		}

		/*
		** The conglomerate is an index, so we need to generate a RowLocation
		** as the last column of the result set.  Notify the ResultColumnList
		** that it needs to do this.  Also tell the RCL whether this is
		** the target of an update, so it can tell the conglomerate controller
		** when it is getting the RowLocation template.  
		*/
		newCols.setIndexRow(heapCD.getConglomerateNumber(), forUpdate());

		return newCols;
	}

	/**
	 * Generation on a FromBaseTable creates a scan on the
	 * optimizer-selected conglomerate.
	 *
	 * @param acb	The ActivationClassBuilder for the class being built
	 * @param mb	the execute() method to be built
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public void generate(ActivationClassBuilder acb,
								MethodBuilder mb)
							throws StandardException
	{
		generateResultSet( acb, mb );

		/*
		** Remember if this base table is the cursor target table, so we can
		** know which table to use when doing positioned update and delete
		*/
		if (cursorTargetTable)
		{
			acb.rememberCursorTarget(mb);
		}
	}

	/**
	 * Generation on a FromBaseTable for a SELECT. This logic was separated
	 * out so that it could be shared with PREPARE SELECT FILTER.
	 *
	 * @param acb	The ExpressionClassBuilder for the class being built
	 * @param mb	The execute() method to be built
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public void generateResultSet(ExpressionClassBuilder acb,
								MethodBuilder mb)
							throws StandardException
	{
// GemStone changes BEGIN
	  final AccessPath trulyTheBestAccessPath = getTrulyTheBestAccessPath();
	  final boolean hasOrList = this.restrictionList.hasOrList();
// GemStone changes END
		/* We must have been a best conglomerate descriptor here */
		if (SanityManager.DEBUG)
		SanityManager.ASSERT(
// GemStone changes BEGIN
		    trulyTheBestAccessPath.getConglomerateDescriptor() != null);
		    /* (original code)
			getTrulyTheBestAccessPath().getConglomerateDescriptor() != null);
		    */
// GemStone changes END

		/* Get the next ResultSet #, so that we can number this ResultSetNode, its
		 * ResultColumnList and ResultSet.
		 */
		assignResultSetNumber();

		/*
		** If we are doing a special scan to get the last row
		** of an index, generate it separately.
		*/
		if (specialMaxScan())
		{
// GemStone changes BEGIN
		  if (hasOrList) {
		    throw StandardException.newException(
		        SQLState.INTERNAL_SKIP_ORLIST_OPTIMIZATION);
		  }
// GemStone changes END
	   		generateMaxSpecialResultSet(acb, mb);
			return;
		}
		
// GemStone changes BEGIN
		/*
		 * lets convert the output to Region.size();
		 */
		if (specialRegionSize()) {
		  generateRegionSizeSpecialResultSet(acb, mb);
		  return;
		}
// GemStone changes END

		/*
		** If we are doing a special distinct scan, generate
		** it separately.
		*/
		if (distinctScan())
		{
// GemStone changes BEGIN
		  if (hasOrList) {
		    throw StandardException.newException(
		        SQLState.INTERNAL_SKIP_ORLIST_OPTIMIZATION);
		  }
// GemStone changes END
	   		generateDistinctScan(acb, mb);
			return;
		}
		
		/*
		 * Referential action dependent table scan, generate it
		 * seperately.
		 */

		if(raDependentScan())
		{
// GemStone changes BEGIN
		  if (hasOrList) {
		    throw StandardException.newException(
		        SQLState.INTERNAL_SKIP_ORLIST_OPTIMIZATION);
		  }
// GemStone changes END
			generateRefActionDependentTableScan(acb, mb);
			return;

		}
// GemStone changes BEGIN
                if (SanityManager.DEBUG) {
                  if (GemFireXDUtils.TraceNCJ) {
                    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                        " createGFEResultSet=" + this.createGFEResultSet()
                            + " in FromBaseTable.generateResultSet for table="
                            + this.tableName + " and qInfo=" + this.qInfo);
                  }
                }
		if (this.createGFEResultSet()) {
		  generateGFEResultSet(acb, mb);
		  return;
		}
		
		JoinStrategy trulyTheBestJoinStrategy =
		    trulyTheBestAccessPath.getJoinStrategy();

                /* (original code)
		JoinStrategy trulyTheBestJoinStrategy =
			getTrulyTheBestAccessPath().getJoinStrategy();
		*/
// GemStone changes END

		// the table scan generator is what we return
		acb.pushGetResultSetFactoryExpression(mb);

// GemStone changes BEGIN
		// for OR optimized list, need to create a scan for each of the
		// element and the top-level scan will be a MultiColumnScanRS
		if (hasOrList) {
		  MethodBuilder resultRowAllocator;
		  // empty nonStoreRestrictionList
		  PredicateList nonStoreRL = (PredicateList)getNodeFactory()
		      .getNode(C_NodeTypes.PREDICATE_LIST, getContextManager());
		  final TransactionController tc =
		      getLanguageConnectionContext().getTransactionCompile();

		  Predicate pred;
		  boolean foundOrList = false;
		  // first generate scan for each of the elements in the OR list
		  for (Object o : this.restrictionList) {
		    pred = (Predicate)o;
		    if (!(pred.getAndNode() instanceof OrListNode)) {
		      continue;
		    }
		    // cannot handle more than one OR list currently
		    SanityManager.ASSERT(!foundOrList, "unexpected multiple "
		        + "OR lists required to be optimized");

		    OrListNode orListNode = (OrListNode)pred.getAndNode();
		    foundOrList = true;
		    List<ElementSpecification> elems = orListNode.getElements();
		    // create an array of scans to pass to MultiColumnScanRS
		    final int numElems = elems.size();
		    final int lockMode = trulyTheBestAccessPath.getLockMode();
		    final boolean tableLocked = (tableDescriptor
		        .getLockGranularity() == TableDescriptor
		            .TABLE_LOCK_GRANULARITY);
		    final int scanIsolationLevel = getCompilerContext()
		        .getScanIsolationLevel();
		    final CostEstimate bestCost = trulyTheBestAccessPath
		        .getCostEstimate();
		    mb.pushNewArray(ClassName.TableScanResultSet, numElems);
		    for (int index = 0; index < numElems; index++) {
		      // dup the NoPutRS[] at top as setArrayElement will pop it
		      mb.dup();

		      // now push the ResultSetFactory; below this will be the
		      // args to the scan ResultSet
		      acb.pushGetResultSetFactoryExpression(mb);

		      final ElementSpecification elem = elems.get(index);
		      // beetle entry 3865: updateable cursor using index
		      int indexColItem = -1;
		      if (cursorTargetTable || getUpdateLocks()) {
		        ConglomerateDescriptor cd = elem.conglom;
		        if (cd.isIndex()) {
		          IndexRowGenerator indexDesc = cd.getIndexDescriptor();
		          int[] baseColPos = indexDesc.baseColumnPositions();
		          boolean[] isAscending = indexDesc.isAscending();
		          int[] indexCols = new int[baseColPos.length];
		          for (int i = 0; i < indexCols.length; i++) {
		            indexCols[i] = isAscending[i]
		                ? baseColPos[i] : -baseColPos[i];
		          }
		          indexColItem = acb.addItem(indexCols);
		        }
		      }

		      // cannot pass "this" below since code all over uses
		      // the best path etc. from this; instead create a new
		      // FromBaseTable, set its relevant fields and then pass it
		      FromBaseTable fbt = new FromBaseTable();
		      fbt.setContextManager(getContextManager());
		      fbt.setNodeType(C_NodeTypes.FROM_BASE_TABLE);
		      fbt.init(this.correlationName, this.tableProperties);
		      fbt.tableName = this.tableName;
		      fbt.updateOrDelete = this.updateOrDelete;		      
		      fbt.resultColumns = this.resultColumns;
		      fbt.origTableName = this.origTableName;
		      fbt.templateColumns = this.templateColumns;
		      fbt.baseConglomerateDescriptor =
		          this.baseConglomerateDescriptor;
		      fbt.tableDescriptor = this.tableDescriptor;
		      fbt.assignResultSetNumber();
		      fbt.setReferencedTableMap(getReferencedTableMap());
		      fbt.setTableNumber(getTableNumber());

		      fbt.initAccessPaths(trulyTheBestAccessPath.getOptimizer());
		      final AccessPath bestAccessPath =
		          fbt.getTrulyTheBestAccessPath();
		      final NestedLoopJoinStrategy joinStrategy =
		          new NestedLoopJoinStrategy();
		      bestAccessPath.setConglomerateDescriptor(elem.conglom);
		      bestAccessPath.setCostEstimate(elem.costEstimate);
		      bestAccessPath.setCoveringIndexScan(isCoveringIndex(
		          elem.conglom));
		      bestAccessPath.setJoinStrategy(joinStrategy);
		      bestAccessPath.setLockMode(lockMode);
		      fbt.getBestAccessPath().copy(bestAccessPath);

		      // TODO: PERF: we can avoid passing in resultRowAllocator
		      // and add null row handling to all TableScanRS and index
		      // scan controllers since MultiColumnTableScanRS only uses
		      // the current RowLocation, but there are null filtering
		      // conditions etc. in TableScanRS impls so needs to be
		      // done carefully
		      // now adjust the template row etc. for indexes; this should
		      // also be avoided and the complete IndexRowToBaseRowRS be
		      // removed
		      //GemStone changes BEGINF
		      //fbt.skipIndexRowToBaseRow = true;
		      fbt.setFlagMask(indexAccesesBaseTable, true);
		      fbt.setFlagMask(optimizeForOffHeap, isOptimizeForOffHeap());
		      fbt.setFlagMask(skipIndexRowToBaseRow, true);
		      this.setFlagMask(indexAccesesBaseTable, true);
                      //GemStone changes END
                      fbt.changeAccessPath();

                      resultRowAllocator = fbt.resultColumns
                          .generateHolderMethod(acb, fbt.referencedCols, null);
                      int nargs = joinStrategy.getScanArgs(
		          tc, mb, fbt, elem.predList, nonStoreRL, acb,
		          elem.bulkFetch, resultRowAllocator, -1,
		          indexColItem, lockMode, tableLocked,
		          scanIsolationLevel, trulyTheBestAccessPath
		              .getOptimizer().getMaxMemoryPerTable(),
		          elem.multiProbing, delayScanOpening(),isOptimizeForOffHeap(),
		          indexAccesesBaseTable());

		      mb.callMethod(VMOpcode.INVOKEINTERFACE, null,
		          joinStrategy.resultSetMethodName(
		              (elem.bulkFetch != UNSET), elem.multiProbing),
		              ClassName.NoPutResultSet, nargs);

		      // cast to TableScanResultSet
		      mb.cast(ClassName.TableScanResultSet);

		      // add the scan to the array passed to MultiColumnScanRS
		      // Stack: ..., TableScanRS[], TableScanRS[], TableScanRS
		      mb.setArrayElement(index);
		    }
		    // at this point all scan ResultSets have been assigned to
		    // the array which becomes the first argument to
		    // MultiColumnScanRS constructor; push remaining args below:
		    //
		    // Activation activation, int scociItem, GeneratedMethod
		    // resultRowAllocator, int resultSetNumber, int colRefItem,
		    // int lockMode, boolean tableLocked, int isolationLevel,
		    // double optimizerEstimatedRowCount,
		    // double optimizerEstimatedCost
		    StaticCompiledOpenConglomInfo scoci = tc
		        .getStaticCompiledConglomInfo(trulyTheBestAccessPath
		            .getConglomerateDescriptor().getConglomerateNumber());
		    acb.pushThisAsActivation(mb);
		    mb.push(acb.addItem(scoci));

		    resultRowAllocator = resultColumns.generateHolderMethod(acb,
		        referencedCols, null);
		    acb.pushMethodReference(mb, resultRowAllocator);
		    mb.push(getResultSetNumber());

		    int colRefItem = -1;
		    if (referencedCols != null) {
		      colRefItem = acb.addItem(referencedCols);
		    }
		    mb.push(colRefItem);
		    mb.push(lockMode);
		    mb.push(tableLocked);
		    mb.push(scanIsolationLevel);
		    mb.push(bestCost.rowCount());
		    mb.push(bestCost.getEstimatedCost());

		    // finally invoke the MultiColumnTableScanRS constructor
		    final int nargs = 11;
		    mb.callMethod(VMOpcode.INVOKEINTERFACE, null,
		        "getMultiColumnTableScanResultSet",
		        ClassName.NoPutResultSet, nargs);
		  }
		  SanityManager.ASSERT(foundOrList, "unexpected hasOrList=true"
		      + " but found no OR list in restrictionList");
		}
		else {
		  int nargs = getScanArguments(acb, mb);
		  mb.callMethod(VMOpcode.INVOKEINTERFACE, (String) null,
		      trulyTheBestJoinStrategy.resultSetMethodName(
		          (bulkFetch != UNSET), multiProbing()),
		          ClassName.NoPutResultSet, nargs);
		}
		/* (original code)
		int nargs = getScanArguments(acb, mb);

		mb.callMethod(VMOpcode.INVOKEINTERFACE, (String) null,
			trulyTheBestJoinStrategy.resultSetMethodName(
				(bulkFetch != UNSET), multiProbing),
			ClassName.NoPutResultSet, nargs);
		*/
// GemStone changes END

		/* If this table is the target of an update or a delete, then we must 
		 * wrap the Expression up in an assignment expression before 
		 * returning.
		 * NOTE - scanExpress is a ResultSet.  We will need to cast it to the
		 * appropriate subclass.
		 * For example, for a DELETE, instead of returning a call to the 
		 * ResultSetFactory, we will generate and return:
		 *		this.SCANRESULTSET = (cast to appropriate ResultSet type) 
		 * The outer cast back to ResultSet is needed so that
		 * we invoke the appropriate method.
		 *										(call to the ResultSetFactory)
		 */
		if ((updateOrDelete == UPDATE) || (updateOrDelete == DELETE))
		{
			mb.cast(ClassName.CursorResultSet);
			mb.putField(acb.getRowLocationScanResultSetName(), ClassName.CursorResultSet);
			mb.cast(ClassName.NoPutResultSet);
		}
	}

	/**
	 * Get the final CostEstimate for this ResultSetNode.
	 *
	 * @return	The final CostEstimate for this ResultSetNode.
	 */
	@Override
  public CostEstimate getFinalCostEstimate()
	{
		return getTrulyTheBestAccessPath().getCostEstimate();
	}
	
        /* helper method used by generateMaxSpecialResultSet and
         * generateDistinctScan to return the name of the index if the 
         * conglomerate is an index. 
         * @param cd   Conglomerate for which we need to push the index name
         * @param mb   Associated MethodBuilder
         * @throws StandardException
         */
        private void pushIndexName(ConglomerateDescriptor cd, MethodBuilder mb) 
          throws StandardException
        {
            if (cd.isConstraint()) {
                DataDictionary dd = getDataDictionary();
                ConstraintDescriptor constraintDesc = 
                    dd.getConstraintDescriptor(tableDescriptor, cd.getUUID());
                mb.push(constraintDesc.getConstraintName());
            } else if (cd.isIndex())  {
                mb.push(cd.getConglomerateName());
            } else {
             // If the conglomerate is the base table itself, make sure we push null.
             //  Before the fix for DERBY-578, we would push the base table name 
             //  and  this was just plain wrong and would cause statistics information to be incorrect.
              mb.pushNull("java.lang.String");
            }
        }
	
        private void generateMaxSpecialResultSet
	(
		ExpressionClassBuilder	acb,
		MethodBuilder mb
	) throws StandardException
	{
		ConglomerateDescriptor cd = getTrulyTheBestAccessPath().getConglomerateDescriptor();
		CostEstimate costEstimate = getFinalCostEstimate();
		int colRefItem = (referencedCols == null) ?
						-1 :
						acb.addItem(referencedCols);
		boolean tableLockGranularity = tableDescriptor.getLockGranularity() == TableDescriptor.TABLE_LOCK_GRANULARITY;
	
		/*
		** getLastIndexKeyResultSet
		** (
		**		activation,			
		**		resultSetNumber,			
		**		resultRowAllocator,			
		**		conglomereNumber,			
		**		tableName,
		**		optimizeroverride			
		**		indexName,			
		**		colRefItem,			
		**		lockMode,			
		**		tableLocked,
		**		isolationLevel,
		**		optimizerEstimatedRowCount,
		**		optimizerEstimatedRowCost,
		**	);
		*/

		acb.pushGetResultSetFactoryExpression(mb);

		acb.pushThisAsActivation(mb);
		mb.push(getResultSetNumber());
		resultColumns.generateHolder(acb, mb, referencedCols, (FormatableBitSet) null);
		mb.push(cd.getConglomerateNumber());
		mb.push(tableDescriptor.getName());
		//User may have supplied optimizer overrides in the sql
		//Pass them onto execute phase so it can be shown in 
		//run time statistics.
		if (tableProperties != null)
			mb.push(com.pivotal.gemfirexd.internal.iapi.util.PropertyUtil.sortProperties(tableProperties));
		else
			mb.pushNull("java.lang.String");
                pushIndexName(cd, mb);
		mb.push(colRefItem);
		mb.push(getTrulyTheBestAccessPath().getLockMode());
		mb.push(tableLockGranularity);
		mb.push(getCompilerContext().getScanIsolationLevel());
		mb.push(costEstimate.singleScanRowCount());
		mb.push(costEstimate.getEstimatedCost());

		mb.callMethod(VMOpcode.INVOKEINTERFACE, (String) null, "getLastIndexKeyResultSet",
					ClassName.NoPutResultSet, 13);


	}

	private void generateDistinctScan
	(
		ExpressionClassBuilder	acb,
		MethodBuilder mb
	) throws StandardException
	{
		ConglomerateDescriptor cd = getTrulyTheBestAccessPath().getConglomerateDescriptor();
		CostEstimate costEstimate = getFinalCostEstimate();
		int colRefItem = (referencedCols == null) ?
						-1 :
						acb.addItem(referencedCols);
		boolean tableLockGranularity = tableDescriptor.getLockGranularity() == TableDescriptor.TABLE_LOCK_GRANULARITY;
	
		/*
		** getDistinctScanResultSet
		** (
		**		activation,			
		**		resultSetNumber,			
		**		resultRowAllocator,			
		**		conglomereNumber,			
		**		tableName,
		**		optimizeroverride			
		**		indexName,			
		**		colRefItem,			
		**		lockMode,			
		**		tableLocked,
		**		isolationLevel,
		**		optimizerEstimatedRowCount,
		**		optimizerEstimatedRowCost,
		**		closeCleanupMethod
		**	);
		*/

		/* Get the hash key columns and wrap them in a formattable */
		int[] hashKeyColumns;

		hashKeyColumns = new int[resultColumns.size()];
		if (referencedCols == null)
		{
			for (int index = 0; index < hashKeyColumns.length; index++)
			{
				hashKeyColumns[index] = index;
			}
		}
		else
		{
			int index = 0;
			for (int colNum = referencedCols.anySetBit();
					colNum != -1;
					colNum = referencedCols.anySetBit(colNum))
			{
				hashKeyColumns[index++] = colNum;
			}
		}

		FormatableIntHolder[] fihArray = 
				FormatableIntHolder.getFormatableIntHolders(hashKeyColumns); 
		FormatableArrayHolder hashKeyHolder = new FormatableArrayHolder(fihArray);
		int hashKeyItem = acb.addItem(hashKeyHolder);
		long conglomNumber = cd.getConglomerateNumber();
		StaticCompiledOpenConglomInfo scoci = getLanguageConnectionContext().
												getTransactionCompile().
													getStaticCompiledConglomInfo(conglomNumber);

		acb.pushGetResultSetFactoryExpression(mb);

     	acb.pushThisAsActivation(mb);
		mb.push(conglomNumber);
		mb.push(acb.addItem(scoci));
 		resultColumns.generateHolder(acb, mb, referencedCols, (FormatableBitSet) null);
		mb.push(getResultSetNumber());
		mb.push(hashKeyItem);
		mb.push(tableDescriptor.getName());
		//User may have supplied optimizer overrides in the sql
		//Pass them onto execute phase so it can be shown in 
		//run time statistics.
		if (tableProperties != null)
			mb.push(com.pivotal.gemfirexd.internal.iapi.util.PropertyUtil.sortProperties(tableProperties));
		else
			mb.pushNull("java.lang.String");
		pushIndexName(cd, mb);
		mb.push(cd.isConstraint());
		mb.push(colRefItem);
		mb.push(getTrulyTheBestAccessPath().getLockMode());
		mb.push(tableLockGranularity);
		mb.push(getCompilerContext().getScanIsolationLevel());
		mb.push(costEstimate.singleScanRowCount());
		mb.push(costEstimate.getEstimatedCost());
		
		mb.callMethod(VMOpcode.INVOKEINTERFACE, (String) null, "getDistinctScanResultSet",
							ClassName.NoPutResultSet, 16);
	}
// GemStone changes BEGIN
  private void generateGFEResultSet(ExpressionClassBuilder acb, MethodBuilder mb)
      throws StandardException {

    acb.pushGetResultSetFactoryExpression(mb);
    acb.pushThisAsActivation(mb);
    MethodBuilder resultRowAllocator = resultColumns.generateHolderMethod(acb,
        referencedCols, (FormatableBitSet)null);
    acb.pushMethodReference(mb, resultRowAllocator);
    mb.push(this.resultSetNumber);
    int rowFormatterItem = -1;
    int fixedColsItem = -1;
    int varColsItem = -1;
    int lobColsItem = -1;
    int allColsItem = -1;
    int allColsWithLobsItem = -1;
    if (this.referencedCols != null) {
      // create the RowFormatter and push the projection fixed/var/LOB columns
      final GemFireContainer container = Misc.getMemStore().getContainer(
          ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
              this.baseConglomerateDescriptor.getConglomerateNumber()));
      if (container != null) {
        final RowFormatter rf = container.getRowFormatter(this.referencedCols);
        if (rf != null) {
          final TIntArrayList fixedCols = new TIntArrayList();
          final TIntArrayList varCols = new TIntArrayList();
          final TIntArrayList lobCols = new TIntArrayList();
          final TIntArrayList allCols = new TIntArrayList();
          final TIntArrayList allColsWithLobs = new TIntArrayList();
          rf.getColumnPositions(fixedCols, varCols, lobCols, allCols,
              allColsWithLobs);
          rowFormatterItem = acb.addItem(rf);
          if (fixedCols.size() > 0) {
            fixedColsItem = acb.addItem(fixedCols.toNativeArray());
          }
          if (varCols.size() > 0) {
            varColsItem = acb.addItem(varCols.toNativeArray());
          }
          if (lobCols.size() > 0) {
            lobColsItem = acb.addItem(lobCols.toNativeArray());
          }
          if (allCols.size() > 0) {
            allColsItem = acb.addItem(allCols.toNativeArray());
          }
          assert allColsWithLobs.size() > 0;
          allColsWithLobsItem = acb.addItem(allColsWithLobs.toNativeArray());
        }
      }
    }
    mb.push(rowFormatterItem);
    mb.push(fixedColsItem);
    mb.push(varColsItem);
    mb.push(lobColsItem);
    mb.push(allColsItem);
    mb.push(allColsWithLobsItem);
    mb.push(getCompilerContext().getScanIsolationLevel());
    mb.push(forUpdate());
    mb.push(acb.addItem(this.qInfo));
    mb.callMethod(VMOpcode.INVOKEINTERFACE, (String)null, "getGFEResultSet",
        ClassName.NoPutResultSet, 12);
  }
// GemStone changes END

	/**
	 * Generation on a FromBaseTable for a referential action dependent table.
	 *
	 * @param acb	The ExpressionClassBuilder for the class being built
	 * @param mb	The execute() method to be built
	 *
	 * @exception StandardException		Thrown on error
	 */

	private void generateRefActionDependentTableScan
	(
		ExpressionClassBuilder	acb,
		MethodBuilder mb
	) throws StandardException
	{

		acb.pushGetResultSetFactoryExpression(mb);

		//get the parameters required to do a table scan
		int nargs = getScanArguments(acb, mb);

		//extra parameters required to create an dependent table result set.
		mb.push(raParentResultSetId);  //id for the parent result set.
		mb.push(fkIndexConglomId);
		mb.push(acb.addItem(fkColArray));
		mb.push(acb.addItem(getDataDictionary().getRowLocationTemplate(
                      getLanguageConnectionContext(), tableDescriptor)));

		int argCount = nargs + 4;
		mb.callMethod(VMOpcode.INVOKEINTERFACE, (String) null, "getRaDependentTableScanResultSet",
							ClassName.NoPutResultSet, argCount);

		if ((updateOrDelete == UPDATE) || (updateOrDelete == DELETE))
		{
			mb.cast(ClassName.CursorResultSet);
			mb.putField(acb.getRowLocationScanResultSetName(), ClassName.CursorResultSet);
			mb.cast(ClassName.NoPutResultSet);
		}

	}



	private int getScanArguments(ExpressionClassBuilder acb,
										  MethodBuilder mb)
		throws StandardException
	{
        // get a function to allocate scan rows of the right shape and size
   	    MethodBuilder resultRowAllocator =
						resultColumns.generateHolderMethod(acb,
													referencedCols,
													(FormatableBitSet) null);

		// pass in the referenced columns on the saved objects
		// chain
		int colRefItem = -1;
		if (referencedCols != null)
		{
			colRefItem = acb.addItem(referencedCols);
		}

		// beetle entry 3865: updateable cursor using index
		int indexColItem = -1;
		if (cursorTargetTable || getUpdateLocks())
		{
			ConglomerateDescriptor cd = getTrulyTheBestAccessPath().getConglomerateDescriptor();
			if (cd.isIndex())
			{
				int[] baseColPos = cd.getIndexDescriptor().baseColumnPositions();
				boolean[] isAscending = cd.getIndexDescriptor().isAscending();
				int[] indexCols = new int[baseColPos.length];
				for (int i = 0; i < indexCols.length; i++)
					indexCols[i] = isAscending[i] ? baseColPos[i] : -baseColPos[i];
				indexColItem = acb.addItem(indexCols);
			}
		}

        AccessPath ap = getTrulyTheBestAccessPath();
		JoinStrategy trulyTheBestJoinStrategy =	ap.getJoinStrategy();

		/*
		** We can only do bulkFetch on NESTEDLOOP
		*/
		if (SanityManager.DEBUG)
		{
			if ( ( ! trulyTheBestJoinStrategy.bulkFetchOK()) &&
				(bulkFetch != UNSET))
			{
				SanityManager.THROWASSERT("bulkFetch should not be set "+
								"for the join strategy " +
								trulyTheBestJoinStrategy.getName());
			}
		}

		int nargs = trulyTheBestJoinStrategy.getScanArgs(
											getLanguageConnectionContext().getTransactionCompile(),
											mb,
											this,
											storeRestrictionList,
											nonStoreRestrictionList,
											acb,
											bulkFetch,
											resultRowAllocator,
											colRefItem,
											indexColItem,
											getTrulyTheBestAccessPath().
																getLockMode(),
											(tableDescriptor.getLockGranularity() == TableDescriptor.TABLE_LOCK_GRANULARITY),
											getCompilerContext().getScanIsolationLevel(),
											ap.getOptimizer().getMaxMemoryPerTable(),
											multiProbing(), delayScanOpening(), isOptimizeForOffHeap(),
											indexAccesesBaseTable()
											);

		return nargs;
	}

	/**
	 * Convert an absolute to a relative 0-based column position.
	 *
	 * @param absolutePosition	The absolute 0-based column position.
	 *
	 * @return The relative 0-based column position.
	 */
	public int mapAbsoluteToRelativeColumnPosition(int absolutePosition)
	{
		if (referencedCols == null)
		{
			return absolutePosition;
		}

		/* setBitCtr counts the # of columns in the row,
		 * from the leftmost to the absolutePosition, that will be
		 * in the partial row returned by the store.  This becomes
		 * the new value for column position.
		 */
		int setBitCtr = 0;
		int bitCtr = 0;
		for ( ; 
			 bitCtr < referencedCols.getLength() && bitCtr < absolutePosition; 
			 bitCtr++)
		{
			if (referencedCols.get(bitCtr))
			{
				setBitCtr++;
			}
		}
		return setBitCtr;
	}

	/**
	 * Get the exposed name for this table, which is the name that can
	 * be used to refer to it in the rest of the query.
	 *
	 * @return	The exposed name of this table.
	 *
	 */
	@Override
  public String getExposedName() 
	{
		if (correlationName != null)
			return correlationName;
		else
			return getOrigTableName().getFullTableName();
	}
	
	/**
	 * Get the exposed table name for this table, which is the name that can
	 * be used to refer to it in the rest of the query.
	 *
	 * @return	TableName The exposed name of this table.
	 *
	 * @exception StandardException  Thrown on error
	 */
	private TableName getExposedTableName() throws StandardException  
	{
		if (correlationName != null)
			return makeTableName(null, correlationName);
		else
			return getOrigTableName();
	}
	
	/**
	 * Return the table name for this table.
	 *
	 * @return	The table name for this table.
	 */

	public TableName getTableNameField()
	{
		return tableName;
	}

	/**
	 * Return a ResultColumnList with all of the columns in this table.
	 * (Used in expanding '*'s.)
	 * NOTE: Since this method is for expanding a "*" in the SELECT list,
	 * ResultColumn.expression will be a ColumnReference.
	 *
	 * @param allTableName		The qualifier on the "*"
	 *
	 * @return ResultColumnList	List of result columns from this table.
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public ResultColumnList getAllResultColumns(TableName allTableName)
			throws StandardException
	{
		return getResultColumnsForList(allTableName, resultColumns, 
				getOrigTableName());
	}

	/**
	 * Build a ResultColumnList based on all of the columns in this FromBaseTable.
	 * NOTE - Since the ResultColumnList generated is for the FromBaseTable,
	 * ResultColumn.expression will be a BaseColumnNode.
	 *
	 * @return ResultColumnList representing all referenced columns
	 *
	 * @exception StandardException		Thrown on error
	 */
	public ResultColumnList genResultColList()
			throws StandardException
	{
		ResultColumnList 			rcList = null;
		ResultColumn	 			resultColumn;
		ValueNode		 			valueNode;
		ColumnDescriptor 			colDesc = null;
		TableName		 			exposedName;

		/* Cache exposed name for this table.
		 * The exposed name becomes the qualifier for each column
		 * in the expanded list.
		 */
		exposedName = getExposedTableName();

		/* Add all of the columns in the table */
		rcList = (ResultColumnList) getNodeFactory().getNode(
										C_NodeTypes.RESULT_COLUMN_LIST,
										getContextManager());
		ColumnDescriptorList cdl = tableDescriptor.getColumnDescriptorList();
		int					 cdlSize = cdl.size();

		for (int index = 0; index < cdlSize; index++)
		{
			/* Build a ResultColumn/BaseColumnNode pair for the column */
			colDesc = cdl.elementAt(index);
			//A ColumnDescriptor instantiated through SYSCOLUMNSRowFactory only has 
			//the uuid set on it and no table descriptor set on it. Since we know here
			//that this columnDescriptor is tied to tableDescriptor, set it so using
			//setTableDescriptor method. ColumnDescriptor's table descriptor is used
			//to get ResultSetMetaData.getTableName & ResultSetMetaData.getSchemaName
			colDesc.setTableDescriptor(tableDescriptor);

			valueNode = (ValueNode) getNodeFactory().getNode(
											C_NodeTypes.BASE_COLUMN_NODE,
											colDesc.getColumnName(),
									  		exposedName,
											colDesc.getType(),
											getContextManager());
			resultColumn = (ResultColumn) getNodeFactory().getNode(
											C_NodeTypes.RESULT_COLUMN,
											colDesc,
											valueNode,
											getContextManager());

			/* Build the ResultColumnList to return */
			rcList.addResultColumn(resultColumn);
		}

		return rcList;
	}

	/**
	 * Augment the RCL to include the columns in the FormatableBitSet.
	 * If the column is already there, don't add it twice.
	 * Column is added as a ResultColumn pointing to a 
	 * ColumnReference.
	 *
	 * @param inputRcl			The original list
	 * @param colsWeWant		bit set of cols we want
	 *
	 * @return ResultColumnList the rcl
	 *
	 * @exception StandardException		Thrown on error
	 */
	public ResultColumnList addColsToList
	(
		ResultColumnList	inputRcl,
		FormatableBitSet				colsWeWant
	)
			throws StandardException
	{
		ResultColumnList 			rcList = null;
		ResultColumn	 			resultColumn;
		ValueNode		 			valueNode;
		ColumnDescriptor 			cd = null;
		TableName		 			exposedName;

		/* Cache exposed name for this table.
		 * The exposed name becomes the qualifier for each column
		 * in the expanded list.
		 */
		exposedName = getExposedTableName();

		/* Add all of the columns in the table */
		ResultColumnList newRcl = (ResultColumnList) getNodeFactory().getNode(
												C_NodeTypes.RESULT_COLUMN_LIST,
												getContextManager());
		ColumnDescriptorList cdl = tableDescriptor.getColumnDescriptorList();
		int					 cdlSize = cdl.size();

		for (int index = 0; index < cdlSize; index++)
		{
			/* Build a ResultColumn/BaseColumnNode pair for the column */
			cd = cdl.elementAt(index);
			int position = cd.getPosition();

			if (!colsWeWant.get(position))
			{
				continue;
			}

			if ((resultColumn = inputRcl.getResultColumn(position)) == null)
			{	
				valueNode = (ValueNode) getNodeFactory().getNode(
												C_NodeTypes.COLUMN_REFERENCE,
												cd.getColumnName(), 
												exposedName,
												getContextManager());
				resultColumn = (ResultColumn) getNodeFactory().
												getNode(
													C_NodeTypes.RESULT_COLUMN,
													cd,
													valueNode,
													getContextManager());
			}

			/* Build the ResultColumnList to return */
			newRcl.addResultColumn(resultColumn);
		}

		return newRcl;
	}

	/**
	 * Return a TableName node representing this FromTable.
	 * @return a TableName node representing this FromTable.
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public TableName getTableName()
			throws StandardException
	{
		TableName tn;

		tn = super.getTableName();

        if(tn != null) {
            if(tn.getSchemaName() == null &&
               correlationName == null)
                   tn.bind(this.getDataDictionary());
        }

		return (tn != null ? tn : tableName);
	}

	// Gemstone changes BEGIN
	public TableName getActualTableName() {
	  return tableName;
	}
	// Gemstone changes END
	/**
		Mark this ResultSetNode as the target table of an updatable
		cursor.
	 */
	@Override
  public boolean markAsCursorTargetTable()
	{
		cursorTargetTable = true;
		return true;
	}

	/**
	 * Is this a table that has a FOR UPDATE
	 * clause? 
	 *
	 * @return true/false
	 */
	@Override
  protected boolean cursorTargetTable()
	{
		return cursorTargetTable;
	}

	/**
	 * Mark as updatable all the columns in the result column list of this
	 * FromBaseTable that match the columns in the given update column list.
	 *
	 * @param updateColumns		A ResultColumnList representing the columns
	 *							to be updated.
	 */
	void markUpdated(ResultColumnList updateColumns)
	{
		resultColumns.markUpdated(updateColumns);
	}

	/**
	 * Search to see if a query references the specifed table name.
	 *
	 * @param name		Table name (String) to search for.
	 * @param baseTable	Whether or not name is for a base table
	 *
	 * @return	true if found, else false
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean referencesTarget(String name, boolean baseTable)
		throws StandardException
	{
		return baseTable && name.equals(getBaseTableName());
	}

	/**
	 * Return true if the node references SESSION schema tables (temporary or permanent)
	 *
	 * @return	true if references SESSION schema tables, else false
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean referencesSessionSchema()
		throws StandardException
	{
		//If base table is a SESSION schema table, then return true. 
		return isSessionSchema(tableDescriptor.getSchemaDescriptor());
	}


	/**
	 * Return whether or not the underlying ResultSet tree will return
	 * a single row, at most.  This method is intended to be used during
	 * generation, after the "truly" best conglomerate has been chosen.
	 * This is important for join nodes where we can save the extra next
	 * on the right side if we know that it will return at most 1 row.
	 *
	 * @return Whether or not the underlying ResultSet tree will return a single row.
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public boolean isOneRowResultSet()	throws StandardException
	{
		// EXISTS FBT will only return a single row
		if (existsBaseTable())
		{
			return true;
		}

		/* For hash join, we need to consider both the qualification
		 * and hash join predicates and we consider them against all
		 * conglomerates since we are looking for any uniqueness
		 * condition that holds on the columns in the hash table, 
		 * otherwise we just consider the predicates in the 
		 * restriction list and the conglomerate being scanned.

		 */
		AccessPath ap = getTrulyTheBestAccessPath();
		JoinStrategy trulyTheBestJoinStrategy = ap.getJoinStrategy();
		PredicateList pl;

		if (trulyTheBestJoinStrategy.isHashJoin())
		{
			pl = (PredicateList) getNodeFactory().getNode(
											C_NodeTypes.PREDICATE_LIST,
											getContextManager());
			if (storeRestrictionList != null)
			{
				pl.nondestructiveAppend(storeRestrictionList);
			}
			if (nonStoreRestrictionList != null)
			{
				pl.nondestructiveAppend(nonStoreRestrictionList);
			}
			return isOneRowResultSet(pl);
		}
		else
		{
			return isOneRowResultSet(getTrulyTheBestAccessPath().
										getConglomerateDescriptor(),
									 restrictionList);
		}
	}

	/**
	 * Return whether or not this is actually a EBT for NOT EXISTS.
	 */
	@Override
  public boolean isNotExists()
	{
		return GemFireXDUtils.isSet(this.flags, isNotExists);
	}

	public boolean isOneRowResultSet(OptimizablePredicateList predList)	throws StandardException
	{
		ConglomerateDescriptor[] cds = tableDescriptor.getConglomerateDescriptors();

		for (int index = 0; index < cds.length; index++)
		{
			if (isOneRowResultSet(cds[index], predList))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Determine whether or not the columns marked as true in
	 * the passed in array are a superset of any unique index
	 * on this table.  
	 * This is useful for subquery flattening and distinct elimination
	 * based on a uniqueness condition.
	 *
	 * @param eqCols	The columns to consider
	 *
	 * @return Whether or not the columns marked as true are a superset
	 */
	protected boolean supersetOfUniqueIndex(boolean[] eqCols)
		throws StandardException
	{
		ConglomerateDescriptor[] cds = tableDescriptor.getConglomerateDescriptors();

		/* Cycle through the ConglomerateDescriptors */
		for (int index = 0; index < cds.length; index++)
		{
			ConglomerateDescriptor cd = cds[index];

			if (! cd.isIndex())
			{
				continue;
			}
			IndexDescriptor id = cd.getIndexDescriptor();

			if (! id.isUnique())
			{
				continue;
			}

			int[] keyColumns = id.baseColumnPositions();

			int inner = 0;
			for ( ; inner < keyColumns.length; inner++)
			{
				if (! eqCols[keyColumns[inner]])
				{
					break;
				}
			}

			/* Did we get a full match? */
			if (inner == keyColumns.length)
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Determine whether or not the columns marked as true in
	 * the passed in join table matrix are a superset of any single column unique index
	 * on this table.  
	 * This is useful for distinct elimination
	 * based on a uniqueness condition.
	 *
	 * @param tableColMap	The columns to consider
	 *
	 * @return Whether or not the columns marked as true for one at least
	 * 	one table are a superset
	 */
	protected boolean supersetOfUniqueIndex(JBitSet[] tableColMap)
		throws StandardException
	{
		ConglomerateDescriptor[] cds = tableDescriptor.getConglomerateDescriptors();

		/* Cycle through the ConglomerateDescriptors */
		for (int index = 0; index < cds.length; index++)
		{
			ConglomerateDescriptor cd = cds[index];

			if (! cd.isIndex())
			{
				continue;
			}
			IndexDescriptor id = cd.getIndexDescriptor();

			if (! id.isUnique())
			{
				continue;
			}

			int[] keyColumns = id.baseColumnPositions();
			int numBits = tableColMap[0].size();
			JBitSet keyMap = new JBitSet(numBits);
			JBitSet resMap = new JBitSet(numBits);

			int inner = 0;
			for ( ; inner < keyColumns.length; inner++)
			{
				keyMap.set(keyColumns[inner]);
			}
			int table = 0;
			for ( ; table < tableColMap.length; table++)
			{
				resMap.setTo(tableColMap[table]);
				resMap.and(keyMap);
				if (keyMap.equals(resMap))
				{
					tableColMap[table].set(0);
					return true;
				}
			}

		}

		return false;
	}

	/**
	 * Get the lock mode for the target table heap of an update or delete
	 * statement.  It is not always MODE_RECORD.  We want the lock on the
	 * heap to be consistent with optimizer and eventually system's decision.
	 * This is to avoid deadlock (beetle 4318).  During update/delete's
	 * execution, it will first use this lock mode we return to lock heap to
	 * open a RowChanger, then use the lock mode that is the optimizer and
	 * system's combined decision to open the actual source conglomerate.
	 * We've got to make sure they are consistent.  This is the lock chart (for
	 * detail reason, see comments below):
	 *		BEST ACCESS PATH			LOCK MODE ON HEAP
	 *   ----------------------		-----------------------------------------
	 *			index					  row lock
	 *
	 *			heap					  row lock if READ_COMMITTED, 
     *			                          REPEATBLE_READ, or READ_UNCOMMITTED &&
     *			                          not specified table lock otherwise, 
     *			                          use optimizer decided best acess 
     *			                          path's lock mode
	 *
	 * @return	The lock mode
	 */
	@Override
  public int updateTargetLockMode()
	{
		/* if best access path is index scan, we always use row lock on heap,
		 * consistent with IndexRowToBaseRowResultSet's openCore().  We don't
		 * need to worry about the correctness of serializable isolation level
		 * because index will have previous key locking if it uses row locking
		 * as well.
		 */
		if (getTrulyTheBestAccessPath().getConglomerateDescriptor().isIndex())
			return TransactionController.MODE_RECORD;

		/* we override optimizer's decision of the lock mode on heap, and
		 * always use row lock if we are read committed/uncommitted or 
         * repeatable read isolation level, and no forced table lock.  
         *
         * This is also reflected in TableScanResultSet's constructor, 
         * KEEP THEM CONSISTENT!  
         *
         * This is to improve concurrency, while maintaining correctness with 
         * serializable level.  Since the isolation level can change between 
         * compilation and execution if the statement is cached or stored, we 
         * encode both the SERIALIZABLE lock mode and the non-SERIALIZABLE
         * lock mode in the returned lock mode if they are different.
		 */
		int isolationLevel = 
            getLanguageConnectionContext().getCurrentIsolationLevel();


		if ((isolationLevel != ExecutionContext.SERIALIZABLE_ISOLATION_LEVEL) &&
			(tableDescriptor.getLockGranularity() != 
					TableDescriptor.TABLE_LOCK_GRANULARITY))
		{
			int lockMode = getTrulyTheBestAccessPath().getLockMode();
			if (lockMode != TransactionController.MODE_RECORD)
				lockMode = (lockMode & 0xff) << 16;
			else
				lockMode = 0;
			lockMode += TransactionController.MODE_RECORD;

			return lockMode;
		}

		/* if above don't apply, use optimizer's decision on heap's lock
		 */
		return getTrulyTheBestAccessPath().getLockMode();
	}

	/**
	 * Return whether or not the underlying ResultSet tree
	 * is ordered on the specified columns.
	 * RESOLVE - This method currently only considers the outermost table 
	 * of the query block.
	 * RESOLVE - We do not currently push method calls down, so we don't
	 * worry about whether the equals comparisons can be against a variant method.
	 *
	 * @param	crs					The specified ColumnReference[]
	 * @param	permuteOrdering		Whether or not the order of the CRs in the array can be permuted
	 * @param	fbtVector			Vector that is to be filled with the FromBaseTable	
	 *
	 * @return	Whether the underlying ResultSet tree
	 * is ordered on the specified column.
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  boolean isOrderedOn(ColumnReference[] crs, boolean permuteOrdering, Vector fbtVector)
				throws StandardException
	{
		/* The following conditions must be met, regardless of the value of permuteOrdering,
		 * in order for the table to be ordered on the specified columns:
		 *	o  Each column is from this table. (RESOLVE - handle joins later)
		 *	o  The access path for this table is an index.
		 */
		// Verify that all CRs are from this table
		for (int index = 0; index < crs.length; index++)
		{
                        // GemStone changes BEGIN
                        // fix for #46727
                        String crsTableName = crs[index].getSource()
                            .findSourceTableName();
                        if (!(ArrayUtils.objectEquals(this.tableDescriptor.getSchemaName()
                            , Misc
                            .getSchemaName(crs[index].getSourceSchemaName(),
                                getLanguageConnectionContext()))
                            &&  this.getBaseTableName().equals(crsTableName))) {
                        /* (original code) 
                          if (crs[index].getTableNumber() != tableNumber)
                        {*/
                        // GemStone changes END
				return false;
			}
		}
		// Verify access path is an index
		ConglomerateDescriptor cd = getTrulyTheBestAccessPath().getConglomerateDescriptor();
		if (! cd.isIndex())
		{
			return false;
		}

		// Now consider whether or not the CRs can be permuted
		boolean isOrdered;
		if (permuteOrdering)
		{
			isOrdered = isOrdered(crs, cd);
		}
		else
		{
			isOrdered = isStrictlyOrdered(crs, cd);
		}
		//GemStone changes BEGIN
		getTrulyTheBestAccessPath().setSupportsMoveToNextKey(isOrdered && cd.getIndexDescriptor().baseColumnPositions().length == crs.length);
		//GemStone changes END
		
		if (fbtVector != null)
		{
			fbtVector.addElement(this);
		}

		return isOrdered;
	}

	/**
	 * Turn off bulk fetch
	 */
	void disableBulkFetch()
	{
		//bulkFetchTurnedOff = true;
	        this.setFlagMask(bulkFetchTurnedOff, true);
		bulkFetch = UNSET;
	}

	/**
	 * Do a special scan for max.
	 */
	void doSpecialMaxScan()
	{
		if (SanityManager.DEBUG)
		{
			if ((restrictionList.size() != 0) || 
				(storeRestrictionList.size() != 0) ||
				(nonStoreRestrictionList.size() != 0))
			{
				SanityManager.THROWASSERT("shouldn't be setting max special scan because there is a restriction");
			}
		}
		//specialMaxScan = true;
		this.setFlagMask(specialMaxScan, true);
	}

	/**
	 * Is it possible to do a distinct scan on this ResultSet tree.
	 * (See SelectNode for the criteria.)
	 *
	 * @param distinctColumns the set of distinct columns
	 * @return Whether or not it is possible to do a distinct scan on this ResultSet tree.
	 */
	@Override
  boolean isPossibleDistinctScan(Set distinctColumns)
	{
		if ((restrictionList != null && restrictionList.size() != 0)) {
			return false;
		}

		HashSet columns = new HashSet();
		for (int i = 0; i < resultColumns.size(); i++) {
			ResultColumn rc = (ResultColumn) resultColumns.elementAt(i);
			columns.add(rc.getExpression());
		}

		return columns.equals(distinctColumns);
	}

	/**
	 * Mark the underlying scan as a distinct scan.
	 */
	@Override
  void markForDistinctScan()
	{
		//distinctScan = true;
	        this.setFlagMask(distinctScan, true);
	}


	/**
	 * @see ResultSetNode#adjustForSortElimination
	 */
	@Override
  void adjustForSortElimination()
	{
		/* NOTE: IRTBR will use a different method to tell us that
		 * it cannot do a bulk fetch as the ordering issues are
		 * specific to a FBT being under an IRTBR as opposed to a
		 * FBT being under a PRN, etc.
		 * So, we just ignore this call for now.
		 */
	}

	/**
	 * @see ResultSetNode#adjustForSortElimination
	 */
	@Override
  void adjustForSortElimination(RequiredRowOrdering rowOrdering)
		throws StandardException
	{
		/* We may have eliminated a sort with the assumption that
		 * the rows from this base table will naturally come back
		 * in the correct ORDER BY order. But in the case of IN
		 * list probing predicates (see DERBY-47) the predicate
		 * itself may affect the order of the rows.  In that case
		 * we need to notify the predicate so that it does the
		 * right thing--i.e. so that it preserves the natural
		 * ordering of the rows as expected from this base table.
		 * DERBY-3279.
		 */
		if (restrictionList != null)
			restrictionList.adjustForSortElimination(rowOrdering);
	}

	/**
	 * Return whether or not this index is ordered on a permutation of the specified columns.
	 *
	 * @param	crs		The specified ColumnReference[]
	 * @param	cd		The ConglomerateDescriptor for the chosen index.
	 *
	 * @return	Whether or not this index is ordered exactly on the specified columns.
	 *
	 * @exception StandardException		Thrown on error
	 */
	private boolean isOrdered(ColumnReference[] crs, ConglomerateDescriptor cd)
						throws StandardException
	{
		/* This table is ordered on a permutation of the specified columns if:
		 *  o  For each key column, until a match has been found for all of the
		 *	   ColumnReferences, it is either in the array of ColumnReferences
		 *	   or there is an equality predicate on it.
		 *	   (NOTE: It is okay to exhaust the key columns before the ColumnReferences
		 *	   if the index is unique.  In other words if we have CRs left over after
		 *	   matching all of the columns in the key then the table is considered ordered
		 *	   iff the index is unique. For example:
		 *		i1 on (c1, c2), unique
		 *		select distinct c3 from t1 where c1 = 1 and c2 = ?; 
		 *	   is ordered on c3 since there will be at most 1 qualifying row.)
		 */
		boolean[] matchedCRs = new boolean[crs.length];

		int nextKeyColumn = 0;
		int[] keyColumns = cd.getIndexDescriptor().baseColumnPositions();
		String[] baseTableColumnNames = cd.getColumnNames();

		// Walk through the key columns
		for ( ; nextKeyColumn < keyColumns.length; nextKeyColumn++)
		{
			boolean currMatch = false;
			// See if the key column is in crs
			for (int nextCR = 0; nextCR < crs.length; nextCR++)
			{
			  // GemStone changes BEGIN
			  // fix for #46727
			  String crsTableName = crs[nextCR].getSource().findSourceTableName();
			  
                                if (ArrayUtils.objectEquals(this.tableDescriptor.getSchemaName(), Misc
                                    .getSchemaName(crs[nextCR].getSourceSchemaName(),
                                        getLanguageConnectionContext()))
                                    && getBaseTableName().equals(crsTableName)
                                    && crs[nextCR].getColumnName().equals(
                                        baseTableColumnNames[keyColumns[nextKeyColumn] - 1]))
                          // (original code) if (crs[nextCR].getColumnNumber() == keyColumns[nextKeyColumn])
                         // GemStone changes END
				{
					matchedCRs[nextCR] = true;
					currMatch = true;
					break;
				}
			}

			// Advance to next key column if we found a match on this one
			if (currMatch)
			{
				continue;
			}

			// Stop search if there is no equality predicate on this key column
			if (storeRestrictionList != null && /* GemStoneAddition */
			    ! storeRestrictionList.hasOptimizableEqualityPredicate(this, keyColumns[nextKeyColumn], true))
			{
				break;
			}
		}

		/* Count the number of matched CRs. The table is ordered if we matched all of them. */
		int numCRsMatched = 0;
		for (int nextCR = 0; nextCR < matchedCRs.length; nextCR++)
		{
			if (matchedCRs[nextCR])
			{
				numCRsMatched++;
			}
		}

		if (numCRsMatched == matchedCRs.length)
		{
			return true;
		}

		/* We didn't match all of the CRs, but if
		 * we matched all of the key columns then
		 * we need to check if the index is unique.
		 */
		if (nextKeyColumn == keyColumns.length)
		{
			if (cd.getIndexDescriptor().isUnique())
			{
				return true;
			}
			else
			{
				return false;
			}
		}
		else
		{
			return false;
		}
	}

	/**
	 * Return whether or not this index is ordered on a permutation of the specified columns.
	 *
	 * @param	crs		The specified ColumnReference[]
	 * @param	cd		The ConglomerateDescriptor for the chosen index.
	 *
	 * @return	Whether or not this index is ordered exactly on the specified columns.
	 *
	 * @exception StandardException		Thrown on error
	 */
	private boolean isStrictlyOrdered(ColumnReference[] crs, ConglomerateDescriptor cd)
						throws StandardException
	{
		/* This table is ordered on the specified columns in the specified order if:
		 *  o  For each ColumnReference, it is either the next key column or there
		 *	   is an equality predicate on all key columns prior to the ColumnReference.
		 *	   (NOTE: If the index is unique, then it is okay to have a suffix of
		 *	   unmatched ColumnReferences because the set is known to be ordered. For example:
		 *		i1 on (c1, c2), unique
		 *		select distinct c3 from t1 where c1 = 1 and c2 = ?; 
		 *	   is ordered on c3 since there will be at most 1 qualifying row.)
		 */
		int nextCR = 0;
		int nextKeyColumn = 0;
		int[] keyColumns = cd.getIndexDescriptor().baseColumnPositions();

		// Walk through the CRs
		for ( ; nextCR < crs.length; nextCR++)
		{
			/* If we've walked through all of the key columns then
			 * we need to check if the index is unique.
			 * Beetle 4402
			 */
			if (nextKeyColumn == keyColumns.length)
			{
				if (cd.getIndexDescriptor().isUnique())
				{
					break;
				}
				else
				{
					return false;
				}
			}
			if (crs[nextCR].getColumnNumber() == keyColumns[nextKeyColumn])
			{
				nextKeyColumn++;
				continue;
			}
			else 
			{
				while (crs[nextCR].getColumnNumber() != keyColumns[nextKeyColumn])
				{
					// Stop if there is no equality predicate on this key column
					if (! storeRestrictionList.hasOptimizableEqualityPredicate(this, keyColumns[nextKeyColumn], true))
					{
						return false;
					}

					// Advance to the next key column
					nextKeyColumn++;

					/* If we've walked through all of the key columns then
					 * we need to check if the index is unique.
					 */
					if (nextKeyColumn == keyColumns.length)
					{
						if (cd.getIndexDescriptor().isUnique())
						{
							break;
						}
						else
						{
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	/**
	 * Is this a one-row result set with the given conglomerate descriptor?
	 */
	private boolean isOneRowResultSet(ConglomerateDescriptor cd,
									OptimizablePredicateList predList)
		throws StandardException
	{
// GemStone changes BEGIN
	  return isOneRowResultSet(cd, predList, true) == ONE_ROW_UNIQUE;
	}

	private int isOneRowResultSet(ConglomerateDescriptor cd,
	    OptimizablePredicateList predList, boolean skipNonUnique)
	    throws StandardException {
// GemStone changes END
		if (predList == null)
		{
// GemStone changes BEGIN
			return ONE_ROW_NONE;
			/* (original code)
			return false;
			*/
// GemStone changes END
		}

		if (SanityManager.DEBUG)
		{
			if (! (predList instanceof PredicateList))
			{
				SanityManager.THROWASSERT(
					"predList should be a PredicateList, but is a " +
					predList.getClass().getName()
				);
			}
		}

		PredicateList restrictionList = (PredicateList) predList;

		if (! cd.isIndex())
		{
// GemStone changes BEGIN
			return ONE_ROW_NONE;
			/* (original code)
			return false;
			*/
// GemStone changes END
		}

		IndexRowGenerator irg =
			cd.getIndexDescriptor();

		// is this a unique index
// GemStone changes BEGIN
		int uniq = irg.isUnique() ? ONE_ROW_UNIQUE : ONE_ROW_NONUNIQUE;
		if (skipNonUnique && (uniq == ONE_ROW_NONUNIQUE)) {
		  return ONE_ROW_NONE;
		/* (original code)
		if (! irg.isUnique())
		{
			return false;
		*/
// GemStone changes END
		}

		int[] baseColumnPositions = irg.baseColumnPositions();

		DataDictionary dd = getDataDictionary();

		// Do we have an exact match on the full key
		for (int index = 0; index < baseColumnPositions.length; index++)
		{
			// get the column number at this position
			int curCol = baseColumnPositions[index];

			/* Is there a pushable equality predicate on this key column?
			 * (IS NULL is also acceptable)
			 */
			if (! restrictionList.hasOptimizableEqualityPredicate(this, curCol, true))
			{
// GemStone changes BEGIN
				return ONE_ROW_NONE;
				/* (original code)
				return false;
				*/
// GemStone changes END
			}

		}

// GemStone changes BEGIN
		return uniq;
		/* (original code)
		return true;
		*/
// GemStone changes END
	}

	private int getDefaultBulkFetch()
		throws StandardException
	{
		int valInt;
		/*
		String valStr = PropertyUtil.getServiceProperty(
						  getLanguageConnectionContext().getTransactionCompile(),
						  LanguageProperties.BULK_FETCH_PROP,
						  LanguageProperties.BULK_FETCH_DEFAULT);
							
		valInt = getIntProperty(valStr, LanguageProperties.BULK_FETCH_PROP);
		*/
		valInt = GemFireXDUtils.DML_BULK_FETCH_SIZE;

		// verify that the specified value is valid
		if (valInt <= 0)
		{
			throw StandardException.newException(SQLState.LANG_INVALID_BULK_FETCH_VALUE, 
					String.valueOf(valInt));
		}

		/*
		** If the value is <= 1, then reset it
		** to UNSET -- this is how customers can
		** override the bulkFetch default to turn
		** it off.
		*/
		return (valInt <= 1) ?
			UNSET : valInt;
	}

	private String getUserSpecifiedIndexName()
	{
		String retval = null;

		if (tableProperties != null)
		{
			retval = tableProperties.getProperty("index");
		}

		return retval;
	}

	/*
	** RESOLVE: This whole thing should probably be moved somewhere else,
	** like the optimizer or the data dictionary.
	*/
	private StoreCostController getStoreCostController(
										ConglomerateDescriptor cd)
			throws StandardException
	{
		return getCompilerContext().getStoreCostController(cd.getConglomerateNumber());
	}

	private StoreCostController getBaseCostController()
			throws StandardException
	{
		return getStoreCostController(baseConglomerateDescriptor);
	}

	private boolean gotRowCount = false;
	private long rowCount = 0;
	private long baseRowCount() throws StandardException
	{
		if (! gotRowCount)
		{
			StoreCostController scc = getBaseCostController();
			rowCount = scc.getEstimatedRowCount();
			gotRowCount = true;
		}

		return rowCount;
	}

	private DataValueDescriptor[] getRowTemplate(
    ConglomerateDescriptor  cd,
    StoreCostController     scc)
			throws StandardException
	{
		/*
		** If it's for a heap scan, just get all the columns in the
		** table.
		*/
		if (! cd.isIndex())
			return templateColumns.buildEmptyRow().getRowArray();

		/* It's an index scan, so get all the columns in the index */
		ExecRow emptyIndexRow = templateColumns.buildEmptyIndexRow(
														tableDescriptor,
														cd,
														scc,
														getDataDictionary());

		return emptyIndexRow.getRowArray();
	}

	private ConglomerateDescriptor getFirstConglom()
		throws StandardException
	{
		getConglomDescs();
		//gemstone changes begin
                //ignore the global hash index @author yjing.
                //fix bug 40379. need to check if the first conglom is GlobalHashIndex or not!
                int index=0; 
                while(index<conglomDescs.length)
                {
                  if(conglomDescs[index].isIndex() 
                     && conglomDescs[index].getIndexDescriptor().
                         indexType().equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
                      index++;
                  }
                  else {
                     break;
                  }
                }          
                if (index < conglomDescs.length)
                  
                {
                        return conglomDescs[index];
                }
                else
                {
                        return null;
                }
                
              //gemstone changes end    
        //      return conglomDescs[0];
              
		
	}

	private ConglomerateDescriptor getNextConglom(ConglomerateDescriptor currCD)
		throws StandardException
	{
		int index = 0;

		for ( ; index < conglomDescs.length; index++)
		{
			if (currCD == conglomDescs[index])
			{
				break;
			}
		}
//Gemstone changes BEGIN
		//ignore the global hash index @author yjing.
		while(index<conglomDescs.length-1)
		{
		  if(conglomDescs[index+1].isIndex() 
		     && conglomDescs[index+1].getIndexDescriptor().
		         indexType().equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
		      index++;
		  }
		  else {
		     break;
		  }
		}
//Gemstone changes END		
		if (index < conglomDescs.length - 1)
		  
		{
			return conglomDescs[index + 1];
		}
		else
		{
			return null;
		}
		
	}

	private void getConglomDescs()
		throws StandardException
	{
		if (conglomDescs == null)
		{
			conglomDescs = tableDescriptor.getConglomerateDescriptors();
		}
	}


	/**
	 * set the Information gathered from the parent table that is 
	 * required to peform a referential action on dependent table.
	 */
	@Override
  public void setRefActionInfo(long fkIndexConglomId, 
								 int[]fkColArray, 
								 String parentResultSetId,
								 boolean dependentScan)
	{


		this.fkIndexConglomId = fkIndexConglomId;
		this.fkColArray = fkColArray;
		this.raParentResultSetId = parentResultSetId;
		//this.raDependentScan = dependentScan;
		this.setFlagMask(raDependentScan, dependentScan);
	}

	/**
	 * Accept a visitor, and call v.visit()
	 * on child nodes as necessary.  
	 * 
	 * @param v the visitor
	 *
	 * @exception StandardException on error
	 */
	@Override
  public Visitable accept(Visitor v) 
	
		throws StandardException
	{

	        Visitable returnNode = super.accept(v);

		if (v.skipChildren(this))
		{
			return returnNode;
		}



		if (nonStoreRestrictionList != null && !v.stopTraversal()) {
			nonStoreRestrictionList.accept(v);
		}
		
		if (restrictionList != null && !v.stopTraversal()) {
			restrictionList.accept(v);
		}

		if (nonBaseTableRestrictionList != null && !v.stopTraversal()) {
			nonBaseTableRestrictionList.accept(v);
		}

		if (requalificationRestrictionList != null && !v.stopTraversal()) {
			requalificationRestrictionList.accept(v);
		}
		
		return returnNode;
	}
// GemStone changes BEGIN

        /**
         * Returns the QueryInfo object reprsenting the table of the 
         * from clause. 
         */
        @Override
        public final QueryInfo computeQueryInfo(QueryInfoContext qic)
            throws StandardException {
          return new TableQueryInfo(this,qic);
        }

        public final void setCreateGFEResultSetTrue(SelectQueryInfo qinfo) {
          //this.createGFEResultSet = true;
          this.setFlagMask(createGFEResultSet, true);
          this.qInfo = qinfo;
        }

        public final boolean isGFEResultSet() {
          return this.createGFEResultSet();
        }

        public final SelectQueryInfo getQueryInfo() {
          return this.qInfo;
        }

        public final ConglomerateDescriptor getConglomerateDescriptor() {
          return this.baseConglomerateDescriptor;
        }

        public final ExecIndexRow getExecIndexRow() 
          throws StandardException {
          ResultColumnList rslst = getResultColumns();
          ExecIndexRow eir = getExecutionFactory().getIndexableRow(rslst.size()); 
          
          for( int idx=1; idx <= rslst.size(); idx++) {
            ResultColumn rcl = rslst.getResultColumn(idx);
            
            if(rcl.getExpression() instanceof CurrentRowLocationNode) {
                  ConglomerateController cc = null;
                  RowLocation rl;
                  
                  cc = getLanguageConnectionContext().
                                  getTransactionCompile().openConglomerate(
                                      baseConglomerateDescriptor.getConglomerateNumber(),
                                          false, 0, TransactionController.MODE_RECORD, TransactionController.ISOLATION_READ_COMMITTED);
                  try
                  {
                          rl = cc.newRowLocationTemplate();
                          eir.setColumn(idx, rl);
                  }
                  finally
                  {
                          if (cc != null)
                          {
                                  cc.close();
                          }
                  }
            } else {
              eir.setColumn(idx, rcl.getExpression().getTypeServices().getNull());
            }
          }
          return eir;
        }
        
        /**
         * This information is required while merging
         * of the ResultSets on the Query node.
         * 
         * @see TableQueryInfo 
         * @return distinct scan the table or not.
         */
        public final boolean isDistinctScan() {
          return distinctScan();
        }

        final void doSpecialRegionSize(ResultColumn parentRC)
            throws StandardException {
          if (SanityManager.DEBUG) {
            if ((restrictionList.size() != 0)
                || (storeRestrictionList != null && storeRestrictionList.size() != 0)
                || (nonStoreRestrictionList != null && nonStoreRestrictionList.size() != 0)) {
              SanityManager
                  .THROWASSERT("shouldn't be setting special region size scan because there is a restriction");
            }
          }
          //now lets replace aggNode with fake ColumnReference 
          ValueNode aggNode = parentRC.getExpression();
          assert aggNode instanceof AggregateNode;
          resultColumns.addColumn(tableName, parentRC.getColumnName(), aggNode
              .getTypeServices());
          ResultColumn newRC = (ResultColumn)resultColumns.elementAt(0);
          
          //this col ref forces PRN generate method to move on and rely on next RS to supply values.
          ColumnReference newColumnRef = (ColumnReference)getNodeFactory().getNode(
              C_NodeTypes.COLUMN_REFERENCE, newRC.getName(), null,
              getContextManager());
          newColumnRef.setSource(newRC);
          newColumnRef.setNestingLevel(this.getLevel());
          newColumnRef.setSourceLevel(this.getLevel());
          
          parentRC.setExpression(newColumnRef);
          if(SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                    "Marking special region size optimization for " + this.getBaseTableName());
            }
          }
          //specialRegionSize = true;
          this.setFlagMask(specialRegionSize, true);
        }
        
        private void generateRegionSizeSpecialResultSet(ExpressionClassBuilder acb,
            MethodBuilder mb) throws StandardException {
          
          ConglomerateDescriptor cd = getTrulyTheBestAccessPath()
              .getConglomerateDescriptor();
          
          /*
          ** getRegionSizeResultSet
          ** (
          **              activation,                     
          **              resultSetNumber,                        
          **              resultRowAllocator,                     
          **              conglomereNumber,                       
          **              tableName
          ** );
          */
      
          acb.pushGetResultSetFactoryExpression(mb);
      
          acb.pushThisAsActivation(mb);
          mb.push(getResultSetNumber());
          resultColumns.generateHolder(acb, mb, referencedCols,
              (FormatableBitSet)null);
          mb.push(cd.getConglomerateNumber());
          mb.push(tableDescriptor.getName());
          // GemStone changes BEGIN
          String withSecondaries="";
          if (explicitSecondaryBucketSet) {
            withSecondaries = Boolean.valueOf(includeSecondaryBuckets).toString();
          }
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceAggreg) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
                  "explicitSecondaryBucket is"
                      + (explicitSecondaryBucketSet ? " set " : " not set")
                      + " includeSecondaryBuckets=" + includeSecondaryBuckets
                      + " withSecondaries=" + withSecondaries);
            }
          }
          mb.push(withSecondaries);
          // GemStone changes END
      
          mb.callMethod(VMOpcode.INVOKEINTERFACE, (String)null,
              "getRegionSizeResultSet", ClassName.NoPutResultSet, 6);
      
        }
        
        /**
         * another decider whether to create GFE activation or local.
         * 
         * @return
         */
        public final boolean isSpecialRegionSize() {
          return specialRegionSize();
        }
        
        @Override
        public void eliminateUnUsedColumns(ResultColumnList parentResultColumn, CollectAndEliminateColumnsVisitor finder)
            throws StandardException {
          
           // don't trim for system tables, as GemFireContainer#newRow returns all the rows.
           if(getTableDescriptor().getTableType() == TableDescriptor.SYSTEM_TABLE_TYPE) {
             return;
           }
           
           // resultColumns = resultColumns.eliminateUnUsedColumns(parentResultColumn, finder);
        }
        
        @Override
        public void delayScanOpening(boolean delay) {
          this.setFlagMask(delayScanOpening, delay);
        }
        
        @Override
        public LocalRegion getRegion(final boolean returnUnInitialized)
            throws StandardException {
          ConglomerateDescriptor conglomDes = getConglomerateDescriptor();
          // First try to obtain the region reference through Gemfire Cache in a
          // straightforward manner
          // Only if the region is not available , like because of Bug 40220
          // Use the base conglomerate ID
          TableDescriptor td = getTableDescriptor();
          final LanguageConnectionContext lcc = getLanguageConnectionContext();
          LocalRegion rgn = (LocalRegion)Misc.getRegion(td, lcc, false, returnUnInitialized);
          if (rgn == null) {
            GemFireTransaction tran = (GemFireTransaction)lcc.getTransactionExecute();
            Conglomerate conglom = tran.findExistingConglomerate(conglomDes
                .getConglomerateNumber());
            MemHeap mh = (MemHeap)conglom;
            rgn = mh.getRegion();
          }
          return rgn;
        }
        
        boolean isPartitionedRegion() throws StandardException {
          return this.getRegion(true).getDataPolicy().withPartitioning();
        }
        
        @Override
        public void getTablesReferencedByRestrictionLists(JBitSet referencedTables) {
          if(this.restrictionList != null) {
            for(int i = 0 ;i < this.restrictionList.size(); ++i) {
              Predicate pred = (Predicate)this.restrictionList.elementAt(i);
              referencedTables.or(pred.referencedSet);
            }
          }
          
          if(this.nonStoreRestrictionList != null) {
            for(int i = 0 ;i < this.nonStoreRestrictionList.size(); ++i) {
              Predicate pred = (Predicate)this.nonStoreRestrictionList.elementAt(i);
              referencedTables.or(pred.referencedSet);
            }
          }
          
          if(this.storeRestrictionList != null) {
            for(int i = 0 ;i < this.storeRestrictionList.size(); ++i) {
              Predicate pred = (Predicate)this.storeRestrictionList.elementAt(i);
              referencedTables.or(pred.referencedSet);
            }
          }
        }
        
      //This is appropriately overriden in SingleChildResultSetNode 
        //and Join node classes
        @Override
        protected void optimizeForOffHeap( boolean shouldOptimize) {
          shouldOptimize = shouldOptimize 
              || (this.resultColumns != null 
              && this.resultColumns.size() < this.tableDescriptor.getNumberOfColumns() 
              && !this.indexAccesesBaseTable());
          this.setFlagMask(optimizeForOffHeap, shouldOptimize);
        }
        
        @Override
        public Optimizable getBaseTable() throws StandardException {
          return this;
        }
        
        @Override
        protected FromBaseTable ncjGetOnlyOneFBTNode() throws StandardException {
          return this;
        }
// GemStone changes END

}
