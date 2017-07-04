/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.authentication.BasicAuthenticationServiceImpl

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

package com.pivotal.gemfirexd.internal.impl.jdbc.authentication;

// GemStone changes BEGIN
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.auth.callback.CredentialInitializer;
import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.SecurityUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.daemon.Serviceable;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleFactory;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;

import java.util.Map;
import java.util.Properties;
// security imports - for SHA-1 digest
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.SQLException;
import java.io.Serializable;
import java.util.Dictionary;

/**
 * This authentication service is the basic Derby user authentication
 * level support.
 *
 * It is activated upon setting gemfirexd.authentication.provider database
 * or system property to 'BUILTIN'.
 * <p>
 * It instantiates & calls the basic User authentication scheme at runtime.
 * <p>
 * In 2.0, users can now be defined as database properties.
 * If gemfirexd.distributedsystem.propertiesOnly is set to true, then in this
 * case, only users defined as database properties for the current database
 * will be considered.
 *
 */
public final class BasicAuthenticationServiceImpl
	extends AuthenticationServiceBase implements UserAuthenticator
        // GemStone changes BEGIN
          , CredentialInitializer {
  
        protected static final String AUTHFACTORYMETHOD = BasicAuthenticationServiceImpl.class
            .getName()
            + factoryMethodForGFEAuth;

        // GemStone changes END

	//
	// ModuleControl implementation (overriden)
	//

	/**
	 *  Check if we should activate this authentication service.
	 */
	public boolean canSupport(String identifier, Properties properties) {

//		if (!requireAuthentication(properties))
//			return false;

		//
		// We check 2 System/Database properties:
		//
		//
		// - if gemfirexd.authentication.provider is set to 'BUILTIN'.
		//
		// and in that case we are the authentication service that should
		// be run.
		//

                // GemStone changes BEGIN
                return checkAndSetSchemeSupported(
                    identifier,
                    properties,
                    Constants.AUTHENTICATION_PROVIDER_BUILTIN);
		/* original code
                authenticationProvider = PropertyUtil.getPropertyFromSet(
                                        properties,
                                                com.pivotal.gemfirexd.internal.iapi.reference.Property.AUTHENTICATION_PROVIDER_PARAMETER);

                 if ( (authenticationProvider != null) &&
                           (StringUtil.SQLEqualsIgnoreCase(authenticationProvider,
                                        com.pivotal.gemfirexd.internal.iapi.reference.Property.AUTHENTICATION_PROVIDER_BUILTIN)))
                        return true;

                return false;
		 */
                // GemStone changes END
	}

	/**
	 * @see com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl#boot
	 * @exception StandardException upon failure to load/boot the expected
	 * authentication service.
	 */
	public void boot(boolean create, Properties properties)
	  throws StandardException {

		// We need authentication
		// setAuthentication(true);

                // GemStone changes BEGIN
                SecurityUtils.prepareUserDefinitionForBuiltInScheme(properties);
                // GemStone changes END
                
		// we call the super in case there is anything to get initialized.
		super.boot(create, properties);

		
		// Initialize the MessageDigest class engine here
		// (we don't need to do that ideally, but there is some
		// overhead the first time it is instantiated.
		// SHA-1 is expected in jdk 1.1x and jdk1.2
		// This is a standard name: check,
		// http://java.sun.com/products/jdk/1.{1,2}
		//					/docs/guide/security/CryptoSpec.html#AppA 
		try {
			MessageDigest digestAlgorithm = MessageDigest.getInstance("SHA-256");
			digestAlgorithm.reset();
			digestAlgorithm = MessageDigest.getInstance("SHA-1");
			digestAlgorithm.reset();

		} catch (NoSuchAlgorithmException nsae) {
			throw Monitor.exceptionStartingModule(nsae);
		}

		// Set ourselves as being ready and loading the proper
		// authentication scheme for this service
		//
		this.setAuthenticationService(this);
	}

	/*
	** UserAuthenticator methods.
	*/

	/**
	 * Authenticate the passed-in user's credentials.
	 *
	 * @param userName		The user's name used to connect to JBMS system
	 * @param userPassword	The user's password used to connect to JBMS system
	 * @param databaseName	The database which the user wants to connect to.
	 * @param info			Additional jdbc connection info.
	 */
	public String /* GemStone changes boolean */	authenticateUser(String userName,
								 String userPassword,
								 String databaseName,
								 Properties info
									)
// GemStone changes BEGIN
	throws SQLException
// GemStone changes END
	{
        // Client security mechanism if any specified
        // Note: Right now it is only used to handle clients authenticating
        // via DRDA SECMEC_USRSSBPWD mechanism
        String clientSecurityMechanism = null;
        // Client security mechanism (if any) short representation
        // Default value is none.
        int secMec = 0;

		// let's check if the user has been defined as a valid user of the
		// JBMS system.
		// We expect to find and match a System property corresponding to the
		// credentials passed-in.
		//
		if (userName == null)
			// We don't tolerate 'guest' user for now.
			return "Null user name";

		String definedUserPassword = null, passedUserPassword = null;

        // If a security mechanism is specified as part of the connection
        // properties, it indicates that we've to account as far as how the
        // password is presented to us - in the case of SECMEC_USRSSBPWD
        // (only expected one at the moment), the password is a substitute
        // one which has already been hashed differently than what we store
        // at the database level (for instance) - this will influence how we
        // assess the substitute password to be legitimate for Derby's
        // BUILTIN authentication scheme/provider.
        if ((clientSecurityMechanism =
                info.getProperty(Attribute.DRDA_SECMEC)) != null)
        {
            secMec = Integer.parseInt(clientSecurityMechanism);
        }

		//
		// Check if user has been defined at the database or/and
		// system level. The user (administrator) can configure it the
		// way he/she wants (as well as forcing users properties to
		// be retrieved at the datbase level only).
		//
        String userNameProperty =
          com.pivotal.gemfirexd.internal.iapi.reference.Property.USER_PROPERTY_PREFIX.concat(
                        userName);

        //SQLF:BC
        String sqlfireUserNameProperty =
            com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_USER_PROPERTY_PREFIX.concat(
                          userName);
		// check if user defined at the database level
		definedUserPassword = getDatabaseProperty(userNameProperty);
                //SQLF:BC
		if (definedUserPassword == null) {
                    definedUserPassword = getDatabaseProperty(sqlfireUserNameProperty);
		}


        if (definedUserPassword != null)
        {
            if (secMec != SECMEC_USRSSBPWD)
            {
                // encrypt passed-in password
// GemStone changes BEGIN
                // check whether defined password is v1 or v2 or v3
                boolean v2Encrypt = false;
                boolean v3Encrypt = true;
                if (!definedUserPassword.startsWith(ID_PATTERN_NEW_SCHEME_V3)) {
                  v3Encrypt = false;
                  v2Encrypt = definedUserPassword
                      .startsWith(ID_PATTERN_NEW_SCHEME_V2);
                }
                passedUserPassword = encryptUserPassword(userName,
                    userPassword, false,
                    v2Encrypt, v3Encrypt);
                /* (original code)
                passedUserPassword = encryptPassword(userPassword, false);
                */
// GemStone changes END
            }
            else
            {
                // Dealing with a client SECMEC - password checking is
                // slightly different and we need to generate a
                // password substitute to compare with the substitute
                // generated one from the client.
                definedUserPassword = substitutePassword(userName,
                                                         definedUserPassword,
                                                         info, true);
                // As SecMec is SECMEC_USRSSBPWD, expected passed-in password
                // to be HexString'ified already
                passedUserPassword = userPassword;
            }
        }
        else
        {
            // check if user defined at the system level
            definedUserPassword = getSystemProperty(userNameProperty);
            //SQLF:BC
            if (definedUserPassword == null) {
              definedUserPassword = getSystemProperty(sqlfireUserNameProperty);
            }
            passedUserPassword = userPassword;

            if ((definedUserPassword != null) &&
                (secMec == SECMEC_USRSSBPWD))
            {
                // Dealing with a client SECMEC - see above comments
                definedUserPassword = substitutePassword(userName,
                                                         definedUserPassword,
                                                         info, false);
            }
// GemStone changes BEGIN
            else if (definedUserPassword != null) {
              // encrypt passed-in password
              boolean v2Encrypt = false;
              boolean v3Encrypt = true;
              if (!definedUserPassword.startsWith(ID_PATTERN_NEW_SCHEME_V3)) {
                v3Encrypt = false;
                v2Encrypt = definedUserPassword
                    .startsWith(ID_PATTERN_NEW_SCHEME_V2);
              }
              passedUserPassword = encryptUserPassword(userName,
                  userPassword, false, v2Encrypt, v3Encrypt);
            }
// GemStone changes END
        }

// GemStone changes BEGIN
          if (GemFireXDUtils.TraceAuthentication) {
            if( definedUserPassword == null) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                  "BUILTIN authentication couldn't find user definition for '"
                      + userName + "'");
            }
            
            if( passedUserPassword == null ) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                  "BUILTIN authentication received improper credentials for "
                      + "user '" + userName + "'");
            }
          }
// GemStone changes END
               // Don't change this to anything else, we don't want to print passwords 
               // in general. This should remain UNDOCUMENTED for us only.
                if (SanityManager.DEBUG_ON("__PINT__")) {
                    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                        "BUILTIN authentication evaluating defined pwd " + definedUserPassword
                            + " with incoming pwd " + passedUserPassword
                            + " for user '" + userName + "'");
                }
                
		if (definedUserPassword == null)
			// no such user found
			return "No such user '" + userName + "'";

		// check if the passwords match
		if (!definedUserPassword.equals(passedUserPassword))
			return (passedUserPassword != null
			    ? "Incorrect password ("+ passedUserPassword + ") for user '" + userName + "' (" +
          definedUserPassword + ")"
			    : "Null password for user '" + userName + "'");

		// NOTE: We do not look at the passed-in database name value as
		// we rely on the authorization service that was put in
		// in 2.0 . (if a database name was passed-in)

		// We do have a valid user
		return null;
	}

        // GemStone changes BEGIN
        /**
         * {@link CredentialInitializer#getCredentials(Properties)}
         * @throws StandardException 
         */
        @Override
        public Properties getCredentials(Properties securityProps)
            throws SQLException {

             return SecurityUtils.getCredentials(securityProps);
            
//                Properties systemProps = PropertyUtil.getSystemProperties(new Properties());
//                for (Map.Entry<Object, Object> sysProp : systemProps.entrySet()) {
//                  String propKey = (String)sysProp.getKey();
//                  Object propValue = sysProp.getValue();
//                  if (propKey.startsWith(Property.USER_PROPERTY_PREFIX)) {
//            
//                    securityProps.put(Attribute.USERNAME_ATTR, propKey
//                        .substring(Property.USER_PROPERTY_PREFIX.length()));
//                    securityProps.put(Attribute.PASSWORD_ATTR, propValue);
//            
//                    if (GemFireXDUtils.TraceAuthentication) {
//                      SanityManager.DEBUG_PRINT(AuthenticationTrace,
//                          "BUILTIN authentication recognized system gemfirexd users "
//                              + propKey);
//                    }
//                    return securityProps;
//                  }
//                }
        }

        @Override
        public String toString() {
          return Constants.AUTHENTICATION_PROVIDER_BUILTIN;
        }
        // GemStone changes END
}
