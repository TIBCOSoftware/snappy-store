/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.sql.dictionary.RoutinePermsDescriptor

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
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.DDdependableFinder;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

/**
 * This class describes rows in the SYS.SYSROUTINEPERMS system table, which keeps track of the routine
 * (procedure and function) permissions that have been granted but not revoked.
 */
public class RoutinePermsDescriptor extends PermissionsDescriptor
{
    private UUID routineUUID;
    private String routineName;
    private boolean hasExecutePermission;
	
	public RoutinePermsDescriptor( DataDictionary dd,
                                   String grantee,
                                   String grantor,
                                   UUID routineUUID,
                                   boolean hasExecutePermission) throws StandardException
	{
        super (dd, grantee, grantor);
        this.routineUUID = routineUUID;
        this.hasExecutePermission = hasExecutePermission;
        //routineUUID can be null only if the constructor with routineePermsUUID
        //has been invoked.
        if (routineUUID != null)
        	routineName = dd.getAliasDescriptor(routineUUID).getObjectName();
	}
	
	public RoutinePermsDescriptor( DataDictionary dd,
                                   String grantee,
                                   String grantor,
                                   UUID routineUUID) throws StandardException
	{
        this( dd, grantee, grantor, routineUUID, true);
	}

    /**
     * This constructor just sets up the key fields of a RoutinePermsDescriptor.
     */
	public RoutinePermsDescriptor( DataDictionary dd,
                                   String grantee,
                                   String grantor) throws StandardException
    {
        this( dd, grantee, grantor, (UUID) null);
    }
	   
    public RoutinePermsDescriptor( DataDictionary dd, UUID routineePermsUUID) 
    throws StandardException
	{
        this( dd, null, null, null, true);
        this.oid = routineePermsUUID;
	}
    
    public int getCatalogNumber()
    {
        return DataDictionary.SYSROUTINEPERMS_CATALOG_NUM;
    }
	
	/*----- getter functions for rowfactory ------*/
    public UUID getRoutineUUID() { return routineUUID;}
    public boolean getHasExecutePermission() { return hasExecutePermission;}

	public String toString()
	{
		return "routinePerms: grantee=" + getGrantee() + 
        ",routinePermsUUID=" + getUUID() +
          ",grantor=" + getGrantor() +
          ",ldapGroup=" + getLdapGroup() + // GemStone addition
          ",routineUUID=" + getRoutineUUID();
	}		

    /**
     * @return true iff the key part of this permissions descriptor equals the key part of another permissions
     *         descriptor.
     */
    public boolean equals( Object other)
    {
        if( !( other instanceof RoutinePermsDescriptor))
            return false;
        RoutinePermsDescriptor otherRoutinePerms = (RoutinePermsDescriptor) other;
        return super.keyEquals( otherRoutinePerms) &&
          routineUUID.equals( otherRoutinePerms.routineUUID);
    }
    
    /**
     * @return the hashCode for the key part of this permissions descriptor
     */
    public int hashCode()
    {
        return super.keyHashCode() + routineUUID.hashCode();
    }
	
	/**
	 * @see PermissionsDescriptor#checkOwner
	 */
	public boolean checkOwner(String authorizationId) throws StandardException
	{
		UUID uuid = getDataDictionary().getAliasDescriptor(routineUUID).getSchemaUUID();
		SchemaDescriptor sd = getDataDictionary().getSchemaDescriptor(uuid, null);
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
		return "Routine Privilege on " + routineName; 
	}

	/**
	 * Get the provider's type.
	 *
	 * @return char		The provider's type.
	 */
	public String getClassType()
	{
		return Dependable.ROUTINE_PERMISSION;
	}

	/**		
		@return the stored form of this provider

			@see Dependable#getDependableFinder
	 */
	public DependableFinder getDependableFinder() 
	{
	    return	new DDdependableFinder(StoredFormatIds.ROUTINE_PERMISSION_FINDER_V01_ID);
	}
}
