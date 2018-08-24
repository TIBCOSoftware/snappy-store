/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColPermsDescriptor

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

package com.pivotal.gemfirexd.internal.iapi.sql.dictionary;


import com.pivotal.gemfirexd.internal.catalog.Dependable;
import com.pivotal.gemfirexd.internal.catalog.DependableFinder;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.DDdependableFinder;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

/**
 * This class describes a row in the SYS.SYSCOLPERMS system table, which keeps
 * the column permissions that have been granted but not revoked.
 */
public class ColPermsDescriptor extends PermissionsDescriptor
{
    private UUID tableUUID;
    private String type;
    private FormatableBitSet columns;
    private String tableName;
	
	public ColPermsDescriptor( DataDictionary dd,
			                   String grantee,
                               String grantor,
                               UUID tableUUID,
                               String type,
                               FormatableBitSet columns) throws StandardException
	{
		super (dd, grantee, grantor);
        this.tableUUID = tableUUID;
        this.type = type;
        this.columns = columns;
        //tableUUID can be null only if the constructor with colPermsUUID
        //has been invoked.
        if (tableUUID != null)
        	tableName = dd.getTableDescriptor(tableUUID).getName();
	}

    /**
     * This constructor just initializes the key fields of a ColPermsDescriptor
     */
	public ColPermsDescriptor( DataDictionary dd,
                               String grantee,
                               String grantor,
                               UUID tableUUID,
                               String type) throws StandardException
    {
        this( dd, grantee, grantor, tableUUID, type, (FormatableBitSet) null);
    }           
    
    public ColPermsDescriptor( DataDictionary dd,
            UUID colPermsUUID) throws StandardException
    {
        super(dd,null,null);
        this.oid = colPermsUUID;
	}
    
    public int getCatalogNumber()
    {
        return DataDictionary.SYSCOLPERMS_CATALOG_NUM;
    }
	
	/*----- getter functions for rowfactory ------*/
    public UUID getTableUUID() { return tableUUID;}
    public String getType() { return type;}
    public FormatableBitSet getColumns() { return columns;}

	public String toString()
	{
		return "colPerms: grantee=" + getGrantee() + 
        ",colPermsUUID=" + getUUID() +
			",grantor=" + getGrantor() +
          ",tableUUID=" + getTableUUID() +
          ",type=" + getType() +
          ",columns=" + getColumns();
	}		

	/**
     * @return true iff the key part of this permissions descriptor equals the key part of another permissions
     *         descriptor.
     */
    public boolean equals( Object other)
    {
        if( !( other instanceof ColPermsDescriptor))
            return false;
        ColPermsDescriptor otherColPerms = (ColPermsDescriptor) other;
        return super.keyEquals( otherColPerms) &&
          tableUUID.equals( otherColPerms.tableUUID) &&
          ((type == null) ? (otherColPerms.type == null) : type.equals( otherColPerms.type));
    }
    
    /**
     * @return the hashCode for the key part of this permissions descriptor
     */
    public int hashCode()
    {
    	return super.keyHashCode() + tableUUID.hashCode() +
		((type == null) ? 0 : type.hashCode());
    }
	
	/**
	 * @see PermissionsDescriptor#checkOwner
	 */
	public boolean checkOwner(String authorizationId) throws StandardException
	{
		SchemaDescriptor sd = getDataDictionary().getTableDescriptor(tableUUID).getSchemaDescriptor();
		String schemaOwner = sd.getAuthorizationId();
		if (schemaOwner.equals(authorizationId)
			|| Misc.checkLDAPGroupOwnership(sd.getSchemaName(), schemaOwner, authorizationId))
			return true;
		else
			return false;
	}

	//////////////////////////////////////////////
	//
	// PROVIDER INTERFACE
	//
	//////////////////////////////////////////////

	/**
	 * Return the name of this Provider.  (Useful for errors.)
	 *
	 * @return String	The name of this provider.
	 */
	public String getObjectName()
	{
		return "Column Privilege on " + tableName; 
	}

	/**
	 * Get the provider's type.
	 *
	 * @return char		The provider's type.
	 */
	public String getClassType()
	{
		return Dependable.COLUMNS_PERMISSION;
	}

	/**		
		@return the stored form of this provider

			@see Dependable#getDependableFinder
	 */
	public DependableFinder getDependableFinder() 
	{
	    return	new DDdependableFinder(StoredFormatIds.COLUMNS_PERMISSION_FINDER_V01_ID);
	}

}
