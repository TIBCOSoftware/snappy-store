/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.db.ConsistencyChecker

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

package com.pivotal.gemfirexd.internal.iapi.db;

// GemStone changes BEGIN

import java.util.Arrays;
import java.util.Collection;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
// GemStone changes END

import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.ConnectionUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/**
 * The ConsistencyChecker class provides static methods for verifying
 * the consistency of the data stored within a database.
 * 
 *
   <p>This class can only be used within an SQL-J statement, a Java procedure or a server side Java method.
   <p>This class can be accessed using the class alias <code> CONSISTENCYCHECKER </code> in SQL-J statements.
 */
public class ConsistencyChecker
{

	/** no requirement for a constructor */
	private ConsistencyChecker() {
	}

	/**
	 * Check the named table, ensuring that all of its indexes are consistent
	 * with the base table.
	 * Use this
	 *  method only within an SQL-J statement; do not call it directly.
	 * <P>When tables are consistent, the method returns true. Otherwise, the method throws an exception.
	 * <p>To check the consistency of a single table:
	 * <p><code>
	 * VALUES ConsistencyChecker::checkTable(<i>SchemaName</i>, <i>TableName</i>)</code></p>
	 * <P>For example, to check the consistency of the table <i>APP.Flights</i>:
	 * <p><code>
	 * VALUES ConsistencyChecker::checkTable('APP', 'FLIGHTS')</code></p>
	 * <p>To check the consistency of all of the tables in the 'APP' schema,
	 * stopping at the first failure: 
	 *
	 * <P><code>SELECT tablename, ConsistencyChecker::checkTable(<br>
	 * 'APP', tablename)<br>
	 * FROM sys.sysschemas s, sys.systables t
	 * WHERE s.schemaname = 'APP' AND s.schemaid = t.schemaid</code>
	 *
	 * <p> To check the consistency of an entire database, stopping at the first failure:
	 *
	 * <p><code>SELECT schemaname, tablename,<br>
	 * ConsistencyChecker::checkTable(schemaname, tablename)<br>
	 * FROM sys.sysschemas s, sys.systables t<br>
	 * WHERE s.schemaid = t.schemaid</code>
	 *
	 *
	 *
	 * @param schemaName	The schema name of the table.
	 * @param tableName		The name of the table
	 *
	 * @return	true, if the table is consistent, exception thrown if inconsistent
	 *
	 * @exception	SQLException	Thrown if some inconsistency
	 *									is found, or if some unexpected
	 *									exception is thrown..
	 */
	public static boolean checkTable(String schemaName, String tableName)
						throws SQLException
	{
		DataDictionary			dd;
		TableDescriptor			td;
		long					baseRowCount = -1;
		long          basePrimaryBucketsRowCount = 0;
		long          baseRedundantBucketsRowCount = 0;
		TransactionController	tc;
		ConglomerateDescriptor	heapCD;
		ConglomerateDescriptor	indexCD;
		ExecRow					baseRow;
		ExecRow					indexRow;
		RowLocation				rl = null;
		RowLocation				scanRL = null;
		ScanController			scan = null;
		int[]					baseColumnPositions;
		int						baseColumns = 0;
		DataValueFactory		dvf;
		long					indexRows;
		long          indexRowsForLocalPrimaryBuckets = 0;
		long          indexRowsForLocalRedundantBuckets = 0;
		ConglomerateController	baseCC = null;
		ConglomerateController	indexCC = null;
		SchemaDescriptor		sd;
		ConstraintDescriptor	constraintDesc;

		LocalRegion region = (LocalRegion)Misc.getRegionForTable(schemaName + "." + tableName, true);
		Set<Integer> localPrimaryBucketSet = null;
		if (region.getDataPolicy().withPartitioning()) {
			localPrimaryBucketSet = ((PartitionedRegion)region)
					.getDataStore().getAllLocalPrimaryBucketIds();
		}


		LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
		tc = lcc.getTransactionExecute();

		try {

            dd = lcc.getDataDictionary();

            dvf = lcc.getDataValueFactory();
            
            ExecutionFactory ef = lcc.getLanguageConnectionFactory().getExecutionFactory();

            sd = dd.getSchemaDescriptor(schemaName, tc, true);
            td = dd.getTableDescriptor(tableName, sd, tc);

            if (td == null)
            {
                throw StandardException.newException(
                    SQLState.LANG_TABLE_NOT_FOUND, 
                    schemaName + "." + tableName);
            }

            /* Skip views */
            if (td.getTableType() == TableDescriptor.VIEW_TYPE)
            {
                return true;
            }

			/* Open the heap for reading */
			baseCC = tc.openConglomerate(
			            td.getHeapConglomerateId(), false, 0, 
				        TransactionController.MODE_TABLE,
					    TransactionController.ISOLATION_SERIALIZABLE);

			/* Check the consistency of the heap */
			baseCC.checkConsistency();

			heapCD = td.getConglomerateDescriptor(td.getHeapConglomerateId());

			/* Get a row template for the base table */
			baseRow = ef.getValueRow(td.getNumberOfColumns());

			/* Fill the row with nulls of the correct type */
			ColumnDescriptorList cdl = td.getColumnDescriptorList();
			int					 cdlSize = cdl.size();

			for (int index = 0; index < cdlSize; index++)
			{
				ColumnDescriptor cd = (ColumnDescriptor) cdl.elementAt(index);
				baseRow.setColumn(cd.getPosition(),
										cd.getType().getNull());
			}

			/* Look at all the indexes on the table */
			ConglomerateDescriptor[] cds = td.getConglomerateDescriptors();
			for (int index = 0; index < cds.length; index++)
			{
				indexCD = cds[index];
//Gemstone changes BEGIN @author yjing
				String indexType=null;
//Gemstone changes END					
				/* Skip the heap */
				if ( ! indexCD.isIndex())
				  continue;
//Gemstone changes BEGIN	@author yjing			
				else {
				  indexType=indexCD.getIndexDescriptor().indexType();
				  //System.out.println("ConsistencyChecker:indexType="+indexType);
				  if(indexType.equals(GfxdConstants.LOCAL_HASH1_INDEX_TYPE)){
				    continue;
				  }
				}
//GemStone changes END
				/* Check the internal consistency of the index */
				indexCC = 
			        tc.openConglomerate(
				        indexCD.getConglomerateNumber(),
                        false,
					    0,
						TransactionController.MODE_TABLE,
	                    TransactionController.ISOLATION_SERIALIZABLE);

				indexCC.checkConsistency();
				indexCC.close();
				indexCC = null;

				/* if index is for a constraint check that the constraint exists */

				if (indexCD.isConstraint())
				{
					constraintDesc = dd.getConstraintDescriptor(td, indexCD.getUUID());
					if (constraintDesc == null)
					{
						throw StandardException.newException(
										SQLState.LANG_OBJECT_NOT_FOUND,
										"CONSTRAINT for INDEX",
										indexCD.getConglomerateName());
					}
				}

				/*
				** Set the base row count when we get to the first index.
				** We do this here, rather than outside the index loop, so
				** we won't do the work of counting the rows in the base table
				** if there are no indexes to check.
				*/
				if (baseRowCount < 0)
				{
					scan = tc.openScan(heapCD.getConglomerateNumber(),
										false,	// hold
										0,		// not forUpdate
									    TransactionController.MODE_TABLE,
									    TransactionController.ISOLATION_SERIALIZABLE,
                                        RowUtil.EMPTY_ROW_BITSET,
										null,	// startKeyValue
										0,		// not used with null start posn.
										null,	// qualifier
										null,	// stopKeyValue
// GemStone changes BEGIN
										0, null);		// not used with null stop posn.
// GemStone changes END
					/* Also, get the row location template for index rows */
					rl = scan.newRowLocationTemplate();
//					scanRL = scan.newRowLocationTemplate();

//					for (baseRowCount = 0; scan.next(); baseRowCount++) {
//					  scan.fetch(baseRow);
//					}

					if (region.getDataPolicy().withPartitioning()) {
						Map<Integer, Integer> bucketSizes = ((PartitionedRegion)region)
								.getDataStore().getSizeLocally();
						baseRowCount = 0;
						basePrimaryBucketsRowCount = 0;
						baseRedundantBucketsRowCount = 0;
						Set<Integer> primaryBuckets = ((PartitionedRegion)region)
								.getDataStore().getAllLocalPrimaryBucketIds();
						for (Map.Entry<Integer, Integer> e : bucketSizes.entrySet()) {
							baseRowCount += e.getValue();
							if (primaryBuckets.contains(e.getKey())) {
								basePrimaryBucketsRowCount += e.getValue();
							} else {
								baseRedundantBucketsRowCount += e.getValue();
							}
						}
					} else {
						baseRowCount = region.size();
					}

					scan.close();
					scan = null;

				}
//Gemstone changes BEGIN
				if(indexType.equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE) || indexType.equals("BTREE")) {
//Gemsotne changes End				
				baseColumnPositions =
						indexCD.getIndexDescriptor().baseColumnPositions();
				baseColumns = baseColumnPositions.length;

				FormatableBitSet indexColsBitSet = new FormatableBitSet();
				for (int i = 0; i < baseColumns; i++)
				{
					indexColsBitSet.grow(baseColumnPositions[i]);
					indexColsBitSet.set(baseColumnPositions[i] - 1);
				}

				/* Get one row template for the index scan, and one for the fetch */
				indexRow = ef.getValueRow(baseColumns + 1);

				/* Fill the row with nulls of the correct type */
				for (int column = 0; column < baseColumns; column++)
				{
					/* Column positions in the data dictionary are one-based */
 					ColumnDescriptor cd = td.getColumnDescriptor(baseColumnPositions[column]);
					indexRow.setColumn(column + 1,
											cd.getType().getNull());
				}

				/* Set the row location in the last column of the index row */
				indexRow.setColumn(baseColumns + 1, rl);

				/* Do a full scan of the index */
				scan = tc.openScan(indexCD.getConglomerateNumber(),
									false,	// hold
									0,		// not forUpdate
								    TransactionController.MODE_TABLE,
						            TransactionController.ISOLATION_SERIALIZABLE,
									(FormatableBitSet) null,
									null,	// startKeyValue
									0,		// not used with null start posn.
									null,	// qualifier
									null,	// stopKeyValue
// GemStone changes BEGIN
									0, null);		// not used with null stop posn.
// GemStone changes END
				
				DataValueDescriptor[] baseRowIndexOrder = 
                    new DataValueDescriptor[baseColumns];
				DataValueDescriptor[] baseObjectArray = baseRow.getRowArray();

				for (int i = 0; i < baseColumns; i++)
				{
					baseRowIndexOrder[i] = baseObjectArray[baseColumnPositions[i] - 1];
				}

				/* Get the index rows and count them */
          indexRowsForLocalPrimaryBuckets = 0;
					indexRowsForLocalRedundantBuckets = 0;
				for (indexRows = 0; scan.fetchNext(indexRow); indexRows++)
				{
					/*
					** Get the base row using the RowLocation in the index row,
					** which is in the last column.  
					*/
// GemStone changes BEGIN
				  RowLocation baseRL = (RowLocation)indexRow
				      .getColumn(baseColumns + 1);
				  baseRL = baseCC.fetch(
				      baseRL, baseRow, indexColsBitSet, false);
				  boolean base_row_exists = baseRL != null;
				  if (base_row_exists) {
				    indexRow.setColumn(baseColumns + 1,baseRL);
						if (region.getDataPolicy().withPartitioning() &&
								localPrimaryBucketSet.contains(baseRL.getBucketID())) {
							indexRowsForLocalPrimaryBuckets ++;
						} else {
							indexRowsForLocalRedundantBuckets ++;
						}
				  }
// GemStone changes END
					/* Throw exception if fetch() returns false */
					if (! base_row_exists)
					{
						String indexName = indexCD.getConglomerateName();
						throw StandardException.newException(SQLState.LANG_INCONSISTENT_ROW_LOCATION, 
									(schemaName + "." + tableName),
									indexName, 
									baseRL.toString(),
									indexRow.toString());
					}

					/* Compare all the column values */
					for (int column = 0; column < baseColumns; column++)
					{
						DataValueDescriptor indexColumn =
							indexRow.getColumn(column + 1);
						DataValueDescriptor baseColumn =
							baseRowIndexOrder[column];

						/*
						** With this form of compare(), null is considered equal
						** to null.
						*/
						if (indexColumn.compare(baseColumn) != 0)
						{
							ColumnDescriptor cd = 
                                td.getColumnDescriptor(
                                    baseColumnPositions[column]);

							throw StandardException.newException(
                                SQLState.LANG_INDEX_COLUMN_NOT_EQUAL, 
                                indexCD.getConglomerateName(),
                                td.getSchemaName(),
                                td.getName(),
                                baseRL.toString(),
                                cd.getColumnName(),
                                indexColumn.toString(),
                                baseColumn.toString(),
                                indexRow.toString());
						}
					}
				}

				/* Clean up after the index scan */
				scan.close();
				scan = null;

				/*
				** The index is supposed to have the same number of rows as the
				** base conglomerate.
				*/
				if (indexRows != baseRowCount)
				{
					if (!region.getDataPolicy().withPartitioning()) {
						throw StandardException.newException(SQLState.LANG_INDEX_ROW_COUNT_MISMATCH,
								indexCD.getConglomerateName(),
								td.getSchemaName(),
								td.getName(),
								Long.toString(indexRows),
								Long.toString(baseRowCount));
					} else {
						throw StandardException.newException(SQLState.LANG_INDEX_ROW_COUNT_MISMATCH_PR,
								indexCD.getConglomerateName(),
								td.getSchemaName() + "." + td.getName(),
								Long.toString(indexRows),
								Long.toString(baseRowCount),
								Long.toString(basePrimaryBucketsRowCount),
								Long.toString(baseRedundantBucketsRowCount),
								Long.toString(indexRowsForLocalPrimaryBuckets),
								Long.toString(indexRowsForLocalRedundantBuckets));
					}
				}
// GemStone changes BEGIN
				}
				else {
				  if(indexType.equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)){
				    ConsistencyChecker.checkGlobalHashIndex(tc, td, baseCC, heapCD, indexCD);
				  }
				  else {
				    SanityManager.THROWASSERT(
				        "unknown indexType=" + indexType);
				  }
				}
// GemStone changes END
			}
			/* check that all constraints have backing index */
			ConstraintDescriptorList constraintDescList = 
				dd.getConstraintDescriptors(td);
			for (int index = 0; index < constraintDescList.size(); index++)
			{
				constraintDesc = constraintDescList.elementAt(index);
				if (constraintDesc.hasBackingIndex())
				{
					ConglomerateDescriptor conglomDesc;

					conglomDesc = td.getConglomerateDescriptor(
							constraintDesc.getConglomerateId());
					if (conglomDesc == null)
					{
						throw StandardException.newException(
										SQLState.LANG_OBJECT_NOT_FOUND,
										"INDEX for CONSTRAINT",
										constraintDesc.getConstraintName());
					}
				}
			}
			
		}
		catch (StandardException se)
		{
			throw PublicAPI.wrapStandardException(se);
		}
		finally
		{
            try
            {
                /* Clean up before we leave */
                if (baseCC != null)
                {
                    baseCC.close();
                    baseCC = null;
                }
                if (indexCC != null)
                {
                    indexCC.close();
                    indexCC = null;
                }
                if (scan != null)
                {
                    scan.close();
                    scan = null;
                }
// GemStone changes BEGIN
                // release DML locks
                if (!tc.isTransactional()) {
                  tc.releaseAllLocks(false, false);
                }
// GemStone changes BEGIN
            }
            catch (StandardException se)
            {
                throw PublicAPI.wrapStandardException(se);
            }
		}

		return true;
	}
// GemStone changes BEGIN
///**
// * This class is added to check the consistency between the base table and global hash index.
// * As we can not iterate all the entries of partitioned regions scattered over several nodes,
// * a weak consistency is be checked. For each entry of a base table partitioned region,
// * there is a index entry in the global hash index. In other words, we do not assure that
// * each entry in a global hash index has a corresponding base table entry.
// * [sumedh] Now added full consistency checking by iterating over the global
// * index and checking that every key matches that of base table.
// *
// * @author yjing
// * @author swale
// */
//  static void checkGlobalHashIndex(TransactionController tc, TableDescriptor td,
//      ConglomerateController baseCC, ConglomerateDescriptor heapCD,
//      ConglomerateDescriptor indexCD) throws StandardException {
//
//    ExecRow baseRow = td.getEmptyExecRow();
//    //DataValueDescriptor[] baseObjectArray = baseRow.getRowArrayClone();
//
//    IndexRowGenerator irg = indexCD.getIndexDescriptor();
//    int[] baseColumnPositions = irg.baseColumnPositions();
//    int baseColumns = baseColumnPositions.length;
//    final DataValueDescriptor[] searchCondition =
//      new DataValueDescriptor[baseColumnPositions.length];
//    ExecIndexRow indexRow = irg.getIndexRowTemplate();
//
//    ScanController scan = tc.openScan(
//        heapCD.getConglomerateNumber(),
//        false, // hold
//        GfxdConstants.SCAN_OPENMODE_FOR_GLOBALINDEX // get proper RowLocation
//          | GfxdConstants.SCAN_OPENMODE_FOR_FULLSCAN, // open a full table scan
//        TransactionController.MODE_TABLE,
//        TransactionController.ISOLATION_SERIALIZABLE, RowUtil.EMPTY_ROW_BITSET,
//        null, // startKeyValue
//        0, // not used with null start posn.
//        null, // qualifier
//        null, // stopKeyValue
//        0, null); // not used with null stop posn.
//    irg.getIndexRow(baseRow, scan.newRowLocationTemplate(), indexRow, null);
//
//    final HashSet<CompositeRegionKey> tableIndexKeys =
//      new HashSet<CompositeRegionKey>();
//    ScanController indexScan = null;
//    CompositeRegionKey key;
//    while (scan.next()) {
//      scan.fetch(baseRow);
//      for (int i = 0; i < baseColumns; ++i) {
//        searchCondition[i] = baseRow.getColumn(baseColumnPositions[i]);
//      }
//      if (indexScan == null) {
//        indexScan = tc.openScan(indexCD.getConglomerateNumber(), false, 0,
//            TransactionController.MODE_TABLE,
//            TransactionController.ISOLATION_SERIALIZABLE,
//            (FormatableBitSet)null,
//            searchCondition, // startKeyValue
//            ScanController.GE,
//            null, // qualifier
//            // added last argument null for activation
//            searchCondition, ScanController.GT, null);
//      }
//      else {
//        indexScan.reopenScan(searchCondition, // startKeyValue
//                             ScanController.GE,
//                             null, // qualifier
//                             searchCondition,
//                             ScanController.GT,
//                             null);
//      }
//      if (!indexScan.next()) {
//        throw StandardException.newException(
//            SQLState.LANG_INCONSISTENT_ROW_LOCATION, td.getQualifiedName(),
//            indexCD.getConglomerateName(), RowUtil.toString(searchCondition),
//            baseRow.toString());
//      }
//      indexScan.fetch(indexRow);
//      key = new CompositeRegionKey(getNewArray(searchCondition,
//          baseColumns, true));
//      tableIndexKeys.add(key);
//      // below no longer works since we do not have RegionKey in the
//      // GlobalRowLocation any more; instead we confirm that the
//      // number of unique rows in index is same as that in the table
//      /*
//      RowLocation baseRL = (RowLocation)indexRow.getColumn(baseColumns + 1);
//      boolean base_row_exists = baseCC.fetch(baseRL, baseObjectArray, null);
//
//      // Throw exception if fetch() returns false
//      if (!base_row_exists) {
//        String indexName = indexCD.getConglomerateName();
//        throw StandardException.newException(
//            SQLState.LANG_INCONSISTENT_ROW_LOCATION,
//            (td.getSchemaName() + "." + td.getName()),
//            indexName,
//            baseRL.toString(),
//            indexRow.toString());
//      }
//
//      //compare the rows, which is obtained by scanning the base table
//      //and by index row location.
//
//      DataValueDescriptor[] scanedBaseRow = baseRow.getRowArray();
//      // Compare all the column values
//      for (int column = 0; column < td.getNumberOfColumns(); column++) {
//        // With this form of compare(), null is considered equal
//        // to null.
//        if (baseObjectArray[column].compare(scanedBaseRow[column]) != 0) {
//          ColumnDescriptor cd = td
//              .getColumnDescriptor(column+1);
//
//          throw StandardException.newException(
//              SQLState.LANG_INDEX_COLUMN_NOT_EQUAL,
//              indexCD.getConglomerateName(),
//              td.getSchemaName(),
//              td.getName(),
//              baseRL.toString(),
//              cd.getColumnName(),
//              RowUtil.toString(baseObjectArray),
//              baseRow.toString()
//              );
//        }
//      }
//      */
//    }
//    // now check the total number of keys in the index
//    int numIndexEntries = 0;
//    if (indexScan != null) {
//      indexScan.close();
//      indexScan = null;
//    }
//    indexScan = tc.openScan(indexCD.getConglomerateNumber(), false, 0,
//        TransactionController.MODE_TABLE,
//        TransactionController.ISOLATION_SERIALIZABLE,
//        (FormatableBitSet)null,
//        null, // null startKeyValue for a full scan
//        ScanController.GE,
//        null, // qualifier
//        null, ScanController.GT, null);
//    DataValueDescriptor[] indexRowArray;
//    while (indexScan.next()) {
//      indexRowArray = indexRow.getRowArray();
//      indexScan.fetch(indexRow);
//      key = new CompositeRegionKey(getNewArray(indexRowArray, baseColumns,
//          false));
//      if (!tableIndexKeys.contains(key)) {
//        throw StandardException.newException(
//            SQLState.LANG_INCONSISTENT_ROW_LOCATION, td.getQualifiedName(),
//            indexCD.getConglomerateName(), key.toString(), indexRow.toString());
//      }
//      ++numIndexEntries;
//    }
//    if (numIndexEntries != tableIndexKeys.size()) {
//      throw StandardException.newException(
//          SQLState.LANG_INDEX_ROW_COUNT_MISMATCH,
//          indexCD.getConglomerateName(), td.getSchemaName(), td.getName(),
//          numIndexEntries, tableIndexKeys.size());
//    }
//    if (indexScan != null) {
//      indexScan.close();
//      indexScan = null;
//    }
//    scan.close();
//    scan = null;
//  }

	static void checkGlobalHashIndex(TransactionController tc, TableDescriptor td,
			ConglomerateController baseCC, ConglomerateDescriptor heapCD,
			ConglomerateDescriptor indexCD) throws StandardException {
		LocalRegion globalIndexRegion = (LocalRegion)Misc.getRegionForTable(
				td.getSchemaName() + "." + indexCD.getDescriptorName(), true);

		ExecRow baseRow = td.getEmptyExecRow();
		//DataValueDescriptor[] baseObjectArray = baseRow.getRowArrayClone();

		IndexRowGenerator irg = indexCD.getIndexDescriptor();
		int[] baseColumnPositions = irg.baseColumnPositions();
		int baseColumns = baseColumnPositions.length;
		final DataValueDescriptor[] searchCondition =
				new DataValueDescriptor[baseColumnPositions.length];
		ExecIndexRow indexRow = irg.getIndexRowTemplate();

		ScanController scan = tc.openScan(
				heapCD.getConglomerateNumber(),
				false, // hold
//				GfxdConstants.SCAN_OPENMODE_FOR_GLOBALINDEX // get proper RowLocation
//						| GfxdConstants.SCAN_OPENMODE_FOR_FULLSCAN, // open a full table scan
				0,
				TransactionController.MODE_TABLE,
				TransactionController.ISOLATION_SERIALIZABLE, RowUtil.EMPTY_ROW_BITSET,
				null, // startKeyValue
				0, // not used with null start posn.
				null, // qualifier
				null, // stopKeyValue
				0, null); // not used with null stop posn.
		irg.getIndexRow(baseRow, scan.newRowLocationTemplate(), indexRow, null);

		int batchSize = 5000;
		Object[] tableIndexKeys = new Object[batchSize];
		Object key;
		int numRows = 0;

		// allocate key objects upfront to be reused
		// for every batch
		if (baseColumns > 1) {
			for (int p = 0; p < batchSize; p++) {
				tableIndexKeys[p] = new CompositeRegionKey();
			}
		}
		while (scan.next()) {
			scan.fetch(baseRow);
			for (int i = 0; i < baseColumns; ++i) {
				searchCondition[i] = baseRow.getColumn(baseColumnPositions[i]);
			}
			if (searchCondition.length == 1) {
				key = searchCondition[0].getClone();
				tableIndexKeys[numRows] = key;
			} else {
				key = getNewArray(searchCondition, baseColumns, true);
				((CompositeRegionKey)tableIndexKeys[numRows]).setPrimaryKey((
						DataValueDescriptor[])key);
			}
			numRows++;

			if ((numRows % batchSize) == 0) {
				checkTableKeysInGlobalIndexRegion(td, indexCD, globalIndexRegion,
						searchCondition, Arrays.asList(tableIndexKeys));
				Arrays.asList(tableIndexKeys);
				numRows = 0;
			}
		}

		if (numRows > 0) {
			Object[] remainingTableIndexKeys = Arrays.copyOfRange(tableIndexKeys,
					0, numRows);
			checkTableKeysInGlobalIndexRegion(td, indexCD, globalIndexRegion,
					searchCondition, Arrays.asList(remainingTableIndexKeys));
		}
		scan.close();
		scan = null;
	}

	private static void checkTableKeysInGlobalIndexRegion(TableDescriptor td,
			ConglomerateDescriptor indexCD, LocalRegion globalIndexRegion,
			DataValueDescriptor[] searchCondition,
			Collection tableIndexKeys) throws StandardException {
		Map globalIndexEntriesMap = globalIndexRegion.getAll(tableIndexKeys);
		for (Object o : tableIndexKeys) {
			if (globalIndexEntriesMap.get(o) == null) {
				DataValueDescriptor keyColumns[] = new
						DataValueDescriptor[searchCondition.length];
				if (searchCondition.length == 1) {
					keyColumns[0] = (DataValueDescriptor)o;
				} else {
					((CompositeRegionKey)o).getKeyColumns(keyColumns);
				}
				throw StandardException.newException(
						SQLState.LANG_INCONSISTENT_GLOBAL_INDEX_KEY, td.getQualifiedName(),
						indexCD.getConglomerateName(), RowUtil.toString(keyColumns));
			}
		}
	}

	private static DataValueDescriptor[] getNewArray(DataValueDescriptor[] row,
      int numColumns, boolean clone) {
    final DataValueDescriptor[] newRow = new DataValueDescriptor[numColumns];
    for (int index = 0; index < numColumns; ++index) {
      if (clone) {
        newRow[index] = row[index].getClone();
      }
      else {
        newRow[index] = row[index];
      }
    }
    return newRow;
  }
// GemStone changes END	
}
