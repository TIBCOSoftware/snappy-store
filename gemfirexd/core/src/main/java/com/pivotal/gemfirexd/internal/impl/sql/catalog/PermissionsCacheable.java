/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.catalog.PermissionsCacheable

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





import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.cache.CacheManager;
import com.pivotal.gemfirexd.internal.iapi.services.cache.Cacheable;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.ConnectionUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.AliasDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColPermsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.PermissionsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.RoutinePermsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TablePermsDescriptor;

/**
 * This class implements a Cacheable for a DataDictionary cache of
 * permissions.
 */
class PermissionsCacheable implements Cacheable
{
	protected final DataDictionaryImpl dd;
	private PermissionsDescriptor permissions;
	
	PermissionsCacheable(DataDictionaryImpl dd)
	{
		this.dd = dd;
	}

	/* Cacheable interface */
	public Cacheable setIdentity(Object key) throws StandardException
	{
		// If the user does not have permission then cache an empty (no permission) descriptor in
		// case the same user asks again. That is particularly important for table permission because
		// we ask about table permission before column permissions. If a user has permission to use a
		// proper subset of the columns we will still ask about table permission every time he tries
		// to access that column subset.
		if( key instanceof TablePermsDescriptor)
		{
			TablePermsDescriptor tablePermsKey = (TablePermsDescriptor) key;
			permissions = dd.getUncachedTablePermsDescriptor( tablePermsKey);
			if( permissions == null)
			{
				// The owner has all privileges unless they have been revoked.
				TableDescriptor td = dd.getTableDescriptor( tablePermsKey.getTableUUID());
				SchemaDescriptor sd = td.getSchemaDescriptor();
				if( sd.isSystemSchema())
					// RESOLVE The access to system tables is hard coded to SELECT only to everyone.
					// Is this the way we want Derby to work? Should we allow revocation of read access
					// to system tables? If so we must explicitly add a row to the SYS.SYSTABLEPERMISSIONS
					// table for each system table when a database is created.
					permissions = new TablePermsDescriptor( dd,
															tablePermsKey.getGrantee(),
															(String) null,
															tablePermsKey.getTableUUID(),
															"Y", "N", "N", "N", "N", "N",
															"N");
				else if (tablePermsKey.getGrantee().equals(sd.getAuthorizationId())
						|| Misc.checkLDAPGroupOwnership(sd.getSchemaName(), sd.getAuthorizationId(),
						tablePermsKey.getGrantee()))
					permissions = new TablePermsDescriptor( dd,
															tablePermsKey.getGrantee(),
															Authorizer.SYSTEM_AUTHORIZATION_ID,
															tablePermsKey.getTableUUID(),
															"Y", "Y", "Y", "Y", "Y", "Y",
															"Y");
				else
					permissions = new TablePermsDescriptor( dd,
															tablePermsKey.getGrantee(),
															(String) null,
															tablePermsKey.getTableUUID(),
															"N", "N", "N", "N", "N", "N",
															"N");
			}
		}
		else if( key instanceof ColPermsDescriptor)
		{
			ColPermsDescriptor colPermsKey = (ColPermsDescriptor) key;
			permissions = dd.getUncachedColPermsDescriptor(colPermsKey );
			if( permissions == null)
				permissions = new ColPermsDescriptor( dd,
													  colPermsKey.getGrantee(),
													  (String) null,
													  colPermsKey.getTableUUID(),
													  colPermsKey.getType(),
													  (FormatableBitSet) null);
		}
		else if( key instanceof RoutinePermsDescriptor)
		{
			RoutinePermsDescriptor routinePermsKey = (RoutinePermsDescriptor) key;
			permissions = dd.getUncachedRoutinePermsDescriptor( routinePermsKey);
			if( permissions == null)
			{
				// The owner has all privileges unless they have been revoked.
				try
				{
					AliasDescriptor ad = dd.getAliasDescriptor( routinePermsKey.getRoutineUUID());
					SchemaDescriptor sd = dd.getSchemaDescriptor( ad.getSchemaUUID(),
											  ConnectionUtil.getCurrentLCC().getTransactionExecute());
					if (sd.isSystemSchema() && !sd.isSchemaWithGrantableRoutines(
					    ad.getUUID() /* GemStoneAddition */))
						permissions = new RoutinePermsDescriptor( dd,
																  routinePermsKey.getGrantee(),
                                                                  (String) null,
																  routinePermsKey.getRoutineUUID(),
																  true);
					else if (routinePermsKey.getGrantee().equals(sd.getAuthorizationId())
						|| Misc.checkLDAPGroupOwnership(sd.getSchemaName(), sd.getAuthorizationId(),
						routinePermsKey.getGrantee()))
						permissions = new RoutinePermsDescriptor( dd,
																  routinePermsKey.getGrantee(),
																  Authorizer.SYSTEM_AUTHORIZATION_ID,
																  routinePermsKey.getRoutineUUID(),
																  true);
				}
				catch( java.sql.SQLException sqle)
				{
					throw StandardException.plainWrapException( sqle);
				}
			}
		}
		else
		{
			if( SanityManager.DEBUG)
				SanityManager.NOTREACHED();
			return null;
		}
		if( permissions != null)
			return this;
		return null;
	} // end of setIdentity

	public Cacheable createIdentity(Object key, Object createParameter) throws StandardException
	{
	  if(key == null)
            return null;
	  if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT( (key instanceof TablePermsDescriptor) ||
								  (key instanceof ColPermsDescriptor) ||
								  (key instanceof RoutinePermsDescriptor),
								  "Invalid class, " + key.getClass().getName()
								  + ", passed as key to PermissionsCacheable.createIdentity");
		}
		
		permissions = (PermissionsDescriptor) ((PermissionsDescriptor)key).clone();
		return this;
	} // end of createIdentity

	public void clearIdentity()
	{
		permissions = null;
	}

	public Object getIdentity()
	{
		return permissions;
	}

	public boolean isDirty()
	{
		return false;
	}

	public void clean(boolean forRemove) throws StandardException
	{
	}
}
