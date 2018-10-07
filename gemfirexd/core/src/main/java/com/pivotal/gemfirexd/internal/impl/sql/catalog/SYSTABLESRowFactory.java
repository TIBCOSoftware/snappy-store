/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSTABLESRowFactory

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

package com.pivotal.gemfirexd.internal.impl.sql.catalog;









// GemStone changes BEGIN
import java.sql.Types;

// GemStone changes END
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.CatalogRowFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDescriptorGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SystemColumn;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TupleDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLBoolean;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLLongvarchar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;

/**
 * Factory for creating a SYSTABLES row.
 *
 *
 * @version 0.1
 */

//GemStone changes BEGIN
public class SYSTABLESRowFactory extends CatalogRowFactory
/* class SYSTABLESRowFactory extends CatalogRowFactory */
//GemStone changes END
{
public static final int		SYSTABLES_COLUMN_COUNT = 21;

	/* Column #s for systables (1 based) */
	public static final int		SYSTABLES_TABLEID = 1;
	public static final int		SYSTABLES_TABLENAME = 2;
	public static final int		SYSTABLES_TABLETYPE = 3;
	public static final int		SYSTABLES_SCHEMAID = 4;
	public static final int		SYSTABLES_SCHEMANAME = 5;
	public static final int		SYSTABLES_LOCKGRANULARITY = 6;
        // GemFireXD extensions
	public static final int         SYSTABLES_SERVERGROUPS = 7;
	public static final int         SYSTABLES_DATAPOLICY = 8;
	public static final int         SYSTABLES_PARTITIONATTRS = 9;
	public static final int         SYSTABLES_RESOLVER = 10;
	public static final int         SYSTABLES_EXPIRATIONATTRS = 11;
	public static final int         SYSTABLES_EVICTIONATTRS = 12;
	public static final int         SYSTABLES_DISKATTRS = 13;
	public static final int         SYSTABLES_LOADER = 14;
	public static final int         SYSTABLES_WRITER = 15;
	public static final int         SYSTABLES_LISTENERS = 16;
	public static final int         SYSTABLES_ASYNCLISTENERS = 17;
	public static final int         SYSTABLES_GATEWAYENABLED = 18;
	public static final int         SYSTABLES_SENDERIDS = 19;
	public static final int         SYSTABLES_OFFHEAPENABLED = 20;
	public static final int         SYSTABLES_ROW_LEVEL_SECURITY_ENABLED = 21;
  
	/* (original derby code)
	private static final String		TABLENAME_STRING = "SYSTABLES";

	protected static final int		SYSTABLES_COLUMN_COUNT = 5;

	protected static final int		SYSTABLES_TABLEID = 1;
	protected static final int		SYSTABLES_TABLENAME = 2;
	protected static final int		SYSTABLES_TABLETYPE = 3;
	protected static final int		SYSTABLES_SCHEMAID = 4;
	protected static final int		SYSTABLES_LOCKGRANULARITY = 5;
	*/
// GemStone changes END

	protected static final int		SYSTABLES_INDEX1_ID = 0;
	protected static final int		SYSTABLES_INDEX1_TABLENAME = 1;
	protected static final int		SYSTABLES_INDEX1_SCHEMAID = 2;

	protected static final int		SYSTABLES_INDEX2_ID = 1;
	protected static final int		SYSTABLES_INDEX2_TABLEID = 1;
	
	// all indexes are unique.

	private	static	final	String[]	uuids =
	{
		 "80000018-00d0-fd77-3ed8-000a0a0b1900"	// catalog UUID
		,"80000028-00d0-fd77-3ed8-000a0a0b1900"	// heap UUID
		,"8000001a-00d0-fd77-3ed8-000a0a0b1900"	// SYSTABLES_INDEX1
		,"8000001c-00d0-fd77-3ed8-000a0a0b1900"	// SYSTABLES_INDEX2
	};

	private static final int[][] indexColumnPositions = 
	{ 
		{ SYSTABLES_TABLENAME, SYSTABLES_SCHEMAID},
		{ SYSTABLES_TABLEID }
	};

	/////////////////////////////////////////////////////////////////////////////
	//
	//	CONSTRUCTORS
	//
	/////////////////////////////////////////////////////////////////////////////

    SYSTABLESRowFactory(UUIDFactory uuidf, ExecutionFactory ef, DataValueFactory dvf)
	{
		super(uuidf,ef,dvf);
		initInfo(SYSTABLES_COLUMN_COUNT, GfxdConstants.SYS_TABLENAME_STRING, indexColumnPositions, (boolean[]) null, uuids);
	}

	/////////////////////////////////////////////////////////////////////////////
	//
	//	METHODS
	//
	/////////////////////////////////////////////////////////////////////////////

	/**
	 * Make a SYSTABLES row
	 *
	 * @return	Row suitable for inserting into SYSTABLES.
	 *
	 * @exception   StandardException thrown on failure
	 */

	@Override
  public ExecRow makeRow(TupleDescriptor td,
						   TupleDescriptor	parent)
					throws StandardException
	{
		UUID						oid;
		String	   				tabSType = null;
		int	   					tabIType;
		ExecRow        			row;
		String					lockGranularity = null;
		String					tableID = null;
		String					schemaID = null;
		String					tableName = null;
    boolean rlsEnabled = false;

		if (td != null)
		{
			/*
			** We only allocate a new UUID if the descriptor doesn't already have one.
			** For descriptors replicated from a Source system, we already have an UUID.
			*/
			TableDescriptor descriptor = (TableDescriptor)td;
			SchemaDescriptor schema = (SchemaDescriptor)parent;

			oid = descriptor.getUUID();
			if ( oid == null )
		    {
				oid = getUUIDFactory().createUUID();
				descriptor.setUUID(oid);
			}
			tableID = oid.toString();
			
			if (SanityManager.DEBUG)
			{
				SanityManager.ASSERT(schema != null, 
							"Schema should not be null unless empty row is true");
				if (schema.getUUID() == null)
				{
					SanityManager.THROWASSERT("schema " + schema + " has a null OID");
				}
			}
		
			schemaID = schema.getUUID().toString();

			tableName = descriptor.getName();

			/* RESOLVE - Table Type should really be a char in the descriptor
			 * T, S, V, S instead of 0, 1, 2, 3
			 */
			tabIType = descriptor.getTableType();
			switch (tabIType)
			{
			    case TableDescriptor.BASE_TABLE_TYPE:
					tabSType = "T";
					break;
			    case TableDescriptor.SYSTEM_TABLE_TYPE:
					tabSType = "S";
					break;
			    case TableDescriptor.VIEW_TYPE:
					tabSType = "V";
					break;		

			    case TableDescriptor.SYNONYM_TYPE:
					tabSType = "A";
					break;

					// GemStone changes BEGIN
					case TableDescriptor.COLUMN_TABLE_TYPE:
					tabSType = "C";
					break;
					// GemStone changes END

			    default:
					if (SanityManager.DEBUG)
						SanityManager.THROWASSERT("invalid table type");
			}
			char[] lockGChar = new char[1];
			lockGChar[0] = descriptor.getLockGranularity();
			lockGranularity = new String(lockGChar);
			rlsEnabled = descriptor.getRowLevelSecurityEnabledFlag();
		}

		/* Insert info into systables */

		/* RESOLVE - It would be nice to require less knowledge about systables
		 * and have this be more table driven.
		 */

		/* Build the row to insert  */
		row = getExecutionFactory().getValueRow(SYSTABLES_COLUMN_COUNT);

		/* 1st column is TABLEID (UUID - char(36)) */
		row.setColumn(SYSTABLES_TABLEID, new SQLChar(tableID));

		/* 2nd column is NAME (varchar(30)) */
		row.setColumn(SYSTABLES_TABLENAME, new SQLVarchar(tableName));

		/* 3rd column is TABLETYPE (char(1)) */
		row.setColumn(SYSTABLES_TABLETYPE, new SQLChar(tabSType));

		/* 4th column is SCHEMAID (UUID - char(36)) */
		row.setColumn(SYSTABLES_SCHEMAID, new SQLChar(schemaID));

		/* 5th column is LOCKGRANULARITY (char(1)) */
		row.setColumn(SYSTABLES_LOCKGRANULARITY, new SQLChar(lockGranularity));

// GemStone changes BEGIN
    String schemaName = "";
    String serverGroups = "";
    if (td != null) {
      final TableDescriptor tableDesc = (TableDescriptor)td;
      final SchemaDescriptor schema = (SchemaDescriptor)parent;
      schemaName = schema.getSchemaName();
      final DistributionDescriptor distribDesc = tableDesc
          .getDistributionDescriptor();
      java.util.SortedSet<String> sgsSet;
      if (distribDesc != null
          && (sgsSet = distribDesc.getServerGroups()) != null) {
        serverGroups = SharedUtils.toCSV(sgsSet);
      }
    }
    row.setColumn(SYSTABLES_SCHEMANAME, new SQLVarchar(schemaName));
    row.setColumn(SYSTABLES_SERVERGROUPS, new SQLVarchar(serverGroups));
    row.setColumn(SYSTABLES_DATAPOLICY, new SQLVarchar("NORMAL"));
    row.setColumn(SYSTABLES_PARTITIONATTRS, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_RESOLVER, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_EXPIRATIONATTRS, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_EVICTIONATTRS, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_DISKATTRS, new SQLLongvarchar(null));
    //row.setColumn(SYSTABLES_DISKDIRS, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_LOADER, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_WRITER, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_LISTENERS, new SQLLongvarchar(null));
    row.setColumn(SYSTABLES_ASYNCLISTENERS, new SQLVarchar(null));
    row.setColumn(SYSTABLES_GATEWAYENABLED, new SQLBoolean(false));
    row.setColumn(SYSTABLES_SENDERIDS, new SQLVarchar(null));
    row.setColumn(SYSTABLES_OFFHEAPENABLED, new SQLBoolean(false));
		row.setColumn(SYSTABLES_ROW_LEVEL_SECURITY_ENABLED, new SQLBoolean(rlsEnabled));
// GemStone changes END
		return row;
	}

	/**
	 * Builds an empty index row.
	 *
	 *	@param	indexNumber	Index to build empty row for.
	 *  @param  rowLocation	Row location for last column of index row
	 *
	 * @return corresponding empty index row
	 * @exception   StandardException thrown on failure
	 */
	ExecIndexRow	buildEmptyIndexRow( int indexNumber,
											RowLocation rowLocation)
			throws StandardException
	{
		int ncols = getIndexColumnCount(indexNumber);
		ExecIndexRow row = getExecutionFactory().getIndexableRow(ncols + 1);

		row.setColumn(ncols + 1, rowLocation);

		switch( indexNumber )
		{
		    case SYSTABLES_INDEX1_ID:
				/* 1st column is TABLENAME (varchar(128)) */
				row.setColumn(1, new SQLVarchar());

				/* 2nd column is SCHEMAID (UUID - char(36)) */
				row.setColumn(2, new SQLChar());

				break;

		    case SYSTABLES_INDEX2_ID:
				/* 1st column is TABLEID (UUID - char(36)) */
				row.setColumn(1,new SQLChar());
				break;
		}	// end switch

		return	row;
	}

	///////////////////////////////////////////////////////////////////////////
	//
	//	ABSTRACT METHODS TO BE IMPLEMENTED BY CHILDREN OF CatalogRowFactory
	//
	///////////////////////////////////////////////////////////////////////////

	/**
	 * Make a TableDescriptor out of a SYSTABLES row
	 *
	 * @param row a SYSTABLES row
	 * @param parentTupleDescriptor	Null for this kind of descriptor.
	 * @param dd dataDictionary
	 *
	 * @return	a table descriptor equivalent to a SYSTABLES row
	 *
	 * @exception   StandardException thrown on failure
	 */

	@Override
  public TupleDescriptor buildDescriptor(
		ExecRow					row,
		TupleDescriptor			parentTupleDescriptor,
		DataDictionary 			dd )
					throws StandardException
	{
		if (SanityManager.DEBUG)
		SanityManager.ASSERT(row.nColumns() == SYSTABLES_COLUMN_COUNT, "Wrong number of columns for a SYSTABLES row");

		DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();

		String	tableUUIDString; 
		String	schemaUUIDString; 
		int		tableTypeEnum;
		String	lockGranularity;
		String	tableName, tableType;
		DataValueDescriptor	col;
		UUID		tableUUID;
		UUID		schemaUUID;
		SchemaDescriptor	schema;
		TableDescriptor		tabDesc;
		boolean rowLevelSecurityEnabled;

		/* 1st column is TABLEID (UUID - char(36)) */
		col = row.getColumn(SYSTABLES_TABLEID);
		tableUUIDString = col.getString();
		tableUUID = getUUIDFactory().recreateUUID(tableUUIDString);


		/* 2nd column is TABLENAME (varchar(128)) */
		col = row.getColumn(SYSTABLES_TABLENAME);
		tableName = col.getString();

		/* 3rd column is TABLETYPE (char(1)) */
		col = row.getColumn(SYSTABLES_TABLETYPE);
		tableType = col.getString();
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(tableType.length() == 1, "Third column type incorrect");
		}
		switch (tableType.charAt(0))
		{
			case 'T' : 
				tableTypeEnum = TableDescriptor.BASE_TABLE_TYPE;
				break;
			case 'S' :
				tableTypeEnum = TableDescriptor.SYSTEM_TABLE_TYPE;
				break;
			case 'V' :
				tableTypeEnum = TableDescriptor.VIEW_TYPE;
				break;
			case 'A' :
				tableTypeEnum = TableDescriptor.SYNONYM_TYPE;
				break;
			// GemStone changes BEGIN
			case 'C' :
				tableTypeEnum = TableDescriptor.COLUMN_TABLE_TYPE;
				break;
			// GemStone changes END
			default:
				if (SanityManager.DEBUG)
				SanityManager.THROWASSERT("Fourth column value invalid");
				tableTypeEnum = -1;
		}

		/* 4th column is SCHEMAID (UUID - char(36)) */
		col = row.getColumn(SYSTABLES_SCHEMAID);
		schemaUUIDString = col.getString();
		schemaUUID = getUUIDFactory().recreateUUID(schemaUUIDString);
		
		schema = dd.getSchemaDescriptor(schemaUUID, null);

		/* 5th column is LOCKGRANULARITY (char(1)) */
		col = row.getColumn(SYSTABLES_LOCKGRANULARITY);
		lockGranularity = col.getString();
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(lockGranularity.length() == 1, "Fifth column type incorrect");
		}
    rowLevelSecurityEnabled = row.getColumn(SYSTABLES_ROW_LEVEL_SECURITY_ENABLED).getBoolean();
		// RESOLVE - Deal with lock granularity
		tabDesc = ddg.newTableDescriptor(tableName, schema, tableTypeEnum,
				lockGranularity.charAt(0), rowLevelSecurityEnabled);
		tabDesc.setUUID(tableUUID);
// GemStone changes BEGIN
    col = row.getColumn(SYSTABLES_SERVERGROUPS);
    final DistributionDescriptor distribDesc = tabDesc
        .getDistributionDescriptorFromContainer();
    if (distribDesc != null) {
      distribDesc.setServerGroups(ServerGroupUtils.getServerGroups(col
          .toString()));
      tabDesc.setDistributionDescriptor(distribDesc);
    }
// GemStone changes END
		return tabDesc;
	}

	/**
	 *	Get the table name out of this SYSTABLES row
	 *
	 * @param row a SYSTABLES row
	 *
	 * @return	string, the table name
	 *
	 * @exception   StandardException thrown on failure
	 */
	protected String getTableName(ExecRow	row)
					throws StandardException
	{
		DataValueDescriptor	col;

		col = row.getColumn(SYSTABLES_TABLENAME);
		return col.getString();
	}


	/**
	 * Builds a list of columns suitable for creating this Catalog.
	 *
	 *
	 * @return array of SystemColumn suitable for making this catalog.
	 */
	@Override
  public SystemColumn[]	buildColumnList()
        throws StandardException
	{
        return new SystemColumn[] {
            SystemColumnImpl.getUUIDColumn("TABLEID", false),
            SystemColumnImpl.getIdentifierColumn("TABLENAME", false),
            SystemColumnImpl.getIndicatorColumn("TABLETYPE"),
            SystemColumnImpl.getUUIDColumn("SCHEMAID", false),
// GemStone changes BEGIN
            SystemColumnImpl.getIdentifierColumn("TABLESCHEMANAME", false),
            SystemColumnImpl.getIndicatorColumn("LOCKGRANULARITY"),
            SystemColumnImpl.getColumn("SERVERGROUPS", Types.LONGVARCHAR, false),
            SystemColumnImpl.getColumn("DATAPOLICY", Types.VARCHAR, false, 24),
            SystemColumnImpl.getColumn("PARTITIONATTRS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("RESOLVER", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("EXPIRATIONATTRS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("EVICTIONATTRS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("DISKATTRS", Types.LONGVARCHAR, true),
            //SystemColumnImpl.getColumn("DISKDIRS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getIdentifierColumn("LOADER", true),
            SystemColumnImpl.getIdentifierColumn("WRITER", true),
            SystemColumnImpl.getColumn("LISTENERS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("ASYNCLISTENERS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("GATEWAYENABLED", Types.BOOLEAN, false),
            SystemColumnImpl.getColumn("GATEWAYSENDERS", Types.LONGVARCHAR, true),
            SystemColumnImpl.getColumn("OFFHEAPENABLED", Types.BOOLEAN, false),
						SystemColumnImpl.getColumn("ROWLEVELSECURITYENABLED", Types.BOOLEAN, false)
            
            /* (original derby code) SystemColumnImpl.getIndicatorColumn("LOCKGRANULARITY"), */
// GemStone changes END
        };
	}

}
