/*

   Derby - Class com.pivotal.gemfirexd.internal.jdbc.Driver20

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.jdbc;




import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.BrokeredConnection;
import com.pivotal.gemfirexd.internal.iapi.jdbc.BrokeredConnectionControl;
import com.pivotal.gemfirexd.internal.iapi.reference.Attribute;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.security.SecurityUtil;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableProperties;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.*;

import java.sql.SQLException;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;

import java.security.Permission;
import java.security.AccessControlException;

import java.util.Properties;

/**
	This class extends the local JDBC driver in order to determine at JBMS
	boot-up if the JVM that runs us does support JDBC 2.0. If it is the case
	then we will load the appropriate class(es) that have JDBC 2.0 new public
	methods and sql types.
*/

public abstract class Driver20 extends InternalDriver implements Driver {

	private static final String[] BOOLEAN_CHOICES = {"false", "true"};

	private Class  antiGCDriverManager;

	/*
	**	Methods from ModuleControl
	*/

	public void boot(boolean create, Properties properties) throws StandardException {

		super.boot(create, properties);

		// Register with the driver manager
		AutoloadedDriver.registerDriverModule( this );

		// hold onto the driver manager to avoid its being garbage collected.
		// make sure the class is loaded by using .class
		antiGCDriverManager = java.sql.DriverManager.class;
	}

	public void stop() {

		super.stop();

		AutoloadedDriver.unregisterDriverModule();
	}
  
	public com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet 
	newEmbedResultSet(EmbedConnection conn, ResultSet results, boolean forMetaData, com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement statement, boolean isAtomic)
		throws SQLException
	{
		return new EmbedResultSet20(conn, results, forMetaData, statement,
								 isAtomic); 
	}
	public abstract BrokeredConnection newBrokeredConnection(BrokeredConnectionControl control);
    /**
     * <p>The getPropertyInfo method is intended to allow a generic GUI tool to 
     * discover what properties it should prompt a human for in order to get 
     * enough information to connect to a database.  Note that depending on
     * the values the human has supplied so far, additional values may become
     * necessary, so it may be necessary to iterate though several calls
     * to getPropertyInfo.
     *
     * @param url The URL of the database to connect to.
     * @param info A proposed list of tag/value pairs that will be sent on
     *          connect open.
     * @return An array of DriverPropertyInfo objects describing possible
     *          properties.  This array may be an empty array if no properties
     *          are required.
     * @exception SQLException if a database-access error occurs.
     */
	public  DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {

		// RESOLVE other properties should be added into this method in the future ... 

        if (info != null) {
			if (Boolean.valueOf(info.getProperty(com.pivotal.gemfirexd.Attribute.SHUTDOWN_ATTR)).booleanValue()) {
	
				// no other options possible when shutdown is set to be true
				return new DriverPropertyInfo[0];
			}
		}

		// at this point we have databaseName, 

		String dbname = InternalDriver.getDatabaseName(url, info);

		// convert the ;name=value attributes in the URL into
		// properties.
		FormatableProperties finfo = getAttributes(url, info);
		info = null; // ensure we don't use this reference directly again.
		boolean encryptDB = Boolean.valueOf(finfo.getProperty(Attribute.DATA_ENCRYPTION)).booleanValue();		
		String encryptpassword = finfo.getProperty(Attribute.BOOT_PASSWORD);

		if (dbname.length() == 0 || (encryptDB = true && encryptpassword == null)) {

			// with no database name we can have shutdown or a database name

			// In future, if any new attribute info needs to be included in this
			// method, it just has to be added to either string or boolean or secret array
			// depending on whether it accepts string or boolean or secret(ie passwords) value. 

			String[][] connStringAttributes = {
				{com.pivotal.gemfirexd.Attribute.DBNAME_ATTR, MessageId.CONN_DATABASE_IDENTITY},
				{Attribute.CRYPTO_PROVIDER, MessageId.CONN_CRYPTO_PROVIDER},
				{Attribute.CRYPTO_ALGORITHM, MessageId.CONN_CRYPTO_ALGORITHM},
				{Attribute.CRYPTO_KEY_LENGTH, MessageId.CONN_CRYPTO_KEY_LENGTH},
				{Attribute.CRYPTO_EXTERNAL_KEY, MessageId.CONN_CRYPTO_EXTERNAL_KEY},
				{Attribute.TERRITORY, MessageId.CONN_LOCALE},
				{com.pivotal.gemfirexd.Attribute.COLLATION, MessageId.CONN_COLLATION},
				{com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, MessageId.CONN_USERNAME_ATTR},
				// GemStone changes BEGIN
                                {com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, MessageId.CONN_USERNAME_ATTR},
				// GemStone changes END
				{Attribute.LOG_DEVICE, MessageId.CONN_LOG_DEVICE},
				{Attribute.ROLL_FORWARD_RECOVERY_FROM, MessageId.CONN_ROLL_FORWARD_RECOVERY_FROM},
				{Attribute.CREATE_FROM, MessageId.CONN_CREATE_FROM},
				{Attribute.RESTORE_FROM, MessageId.CONN_RESTORE_FROM},
			};

			String[][] connBooleanAttributes = {
				{com.pivotal.gemfirexd.Attribute.SHUTDOWN_ATTR, MessageId.CONN_SHUT_DOWN_CLOUDSCAPE},
				{com.pivotal.gemfirexd.Attribute.CREATE_ATTR, MessageId.CONN_CREATE_DATABASE},
				{Attribute.DATA_ENCRYPTION, MessageId.CONN_DATA_ENCRYPTION},
				{com.pivotal.gemfirexd.Attribute.UPGRADE_ATTR, MessageId.CONN_UPGRADE_DATABASE},
				};

			String[][] connStringSecretAttributes = {
				{Attribute.BOOT_PASSWORD, MessageId.CONN_BOOT_PASSWORD},
				{com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, MessageId.CONN_PASSWORD_ATTR},
				};

			
			DriverPropertyInfo[] optionsNoDB = new 	DriverPropertyInfo[connStringAttributes.length+
																	  connBooleanAttributes.length+
			                                                          connStringSecretAttributes.length];
			
			int attrIndex = 0;
			for( int i = 0; i < connStringAttributes.length; i++, attrIndex++ )
			{
				optionsNoDB[attrIndex] = new DriverPropertyInfo(connStringAttributes[i][0], 
									  finfo.getProperty(connStringAttributes[i][0]));
				optionsNoDB[attrIndex].description = MessageService.getTextMessage(connStringAttributes[i][1]);
			}

			optionsNoDB[0].choices = Monitor.getMonitor().getServiceList(Property.DATABASE_MODULE);
			// since database name is not stored in FormatableProperties, we
			// assign here explicitly
			optionsNoDB[0].value = dbname;

			for( int i = 0; i < connStringSecretAttributes.length; i++, attrIndex++ )
			{
				optionsNoDB[attrIndex] = new DriverPropertyInfo(connStringSecretAttributes[i][0], 
									  (finfo.getProperty(connStringSecretAttributes[i][0]) == null? "" : "****"));
				optionsNoDB[attrIndex].description = MessageService.getTextMessage(connStringSecretAttributes[i][1]);
			}

			for( int i = 0; i < connBooleanAttributes.length; i++, attrIndex++ )
			{
				optionsNoDB[attrIndex] = new DriverPropertyInfo(connBooleanAttributes[i][0], 
           		    Boolean.valueOf(finfo == null? "" : finfo.getProperty(connBooleanAttributes[i][0])).toString());
				optionsNoDB[attrIndex].description = MessageService.getTextMessage(connBooleanAttributes[i][1]);
				optionsNoDB[attrIndex].choices = BOOLEAN_CHOICES;				
			}

			return optionsNoDB;
		}

		return new DriverPropertyInfo[0];
	}

    /**
     * Checks for System Privileges.
     *
     * @param user The user to be checked for having the permission
     * @param perm The permission to be checked
     * @throws AccessControlException if permissions are missing
     * @throws Exception if the privileges check fails for some other reason
     */
    void checkSystemPrivileges(String user,
                                      Permission perm)
        throws Exception {
        SecurityUtil.checkUserHasPermission(user, perm);
    }
}
