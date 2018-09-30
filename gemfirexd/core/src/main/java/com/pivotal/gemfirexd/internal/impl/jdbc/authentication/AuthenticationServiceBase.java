/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase

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
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.SecurityLogWriter;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.gemstone.gemfire.security.AuthInitialize;
import com.gemstone.gemfire.security.AuthenticationFailedException;
import com.gemstone.gemfire.security.Authenticator;
// GemStone changes END


import com.gemstone.gemfire.security.GemFireSecurityException;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.auth.callback.CredentialInitializer;
import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.SecurityUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.daemon.Serviceable;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleSupportable;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyFactory;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertySetCallback;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.SQLException;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Dictionary;
import java.util.Properties;

import javax.naming.Context;

/**
 * This is the authentication service base class.
 * <p>
 * There can be 1 Authentication Service for the whole Derby
 * system and/or 1 authentication per database.
 * In a near future, we intend to allow multiple authentication services
 * per system and/or per database.
 * <p>
 * It should be extended by the specialized authentication services.
 *
 * IMPORTANT NOTE:
 * --------------
 * User passwords are encrypted using SHA-1 message digest algorithm
 * if they're stored in the database; otherwise they are not encrypted
 * if they were defined at the system level.
 * SHA-1 digest is single hash (one way) digest and is considered very
 * secure (160 bits).
 *
 */
public abstract class AuthenticationServiceBase
	implements AuthenticationService, ModuleControl, ModuleSupportable, PropertySetCallback 
        // GemStone changes BEGIN
        , AuthInitialize , Authenticator {
  
        /**
         *   Credentials that successfully booted the database. 
         */
        private final Properties bootCredentials = new Properties();
        private Properties bootProperties;

        private boolean isPeerAuthenticationService = false;
      // GemStone changes END

	protected UserAuthenticator authenticationScheme; 

	// required to retrieve service properties
	private AccessFactory store;
	
	private LogWriterI18n securitylogger;

	/**
		Trace flag to trace authentication operations
	*/
	public static final String AuthenticationTrace =
	  //GemStone changes BEGIN
//						SanityManager.DEBUG ? "AuthenticationTrace" : null;
                                                GfxdConstants.TRACE_AUTHENTICATION;
	
	public static final String factoryMethodForGFEAuth = ".getPeerAuthenticationService";
	
	private static AuthenticationServiceBase peerAuthenticationService;

	// This flag gets turned on during cache.close() after the VM is booted successfully;
	private volatile boolean isShuttingDown = false;
        //GemStone changes END

	/**
		Pattern that is prefixed to the stored password in the new authentication scheme
	*/
// GemStone changes BEGIN
	public static final String ID_PATTERN_NEW_SCHEME_V3 = "v33b60";
	public static final String ID_PATTERN_NEW_SCHEME_V2 = "v23b60";
	public static final String ID_PATTERN_NEW_SCHEME_V1 = "3b60";
	public static final String ID_PATTERN_LDAP_SCHEME_V1 = "v13b607k2j6";
	/* (original code)
	public static final String ID_PATTERN_NEW_SCHEME = "3b60";
	*/
// GemStone changes END

    /**
        Userid with Strong password substitute DRDA security mechanism
    */
    protected static final int SECMEC_USRSSBPWD = 8;

	/**
		Length of the encrypted password in the new authentication scheme
		See Beetle4601
	*/
	public static final int MAGICLEN_NEWENCRYPT_SCHEME=44;

	//
	// constructor
	//
	public AuthenticationServiceBase() {
	}

	protected void setAuthenticationService(UserAuthenticator aScheme) {
		// specialized class is the principal caller.
		this.authenticationScheme = aScheme;

		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(this.authenticationScheme != null, 
				"There is no authentication scheme for that service!");
		
// Gemstone changes BEGIN
			if (GemFireXDUtils.TraceAuthentication) {
			/* if (SanityManager.DEBUG_ON(AuthenticationTrace)) { */
// GemStone changes END

				java.io.PrintWriter iDbgStream =
					SanityManager.GET_DEBUG_STREAM();

				iDbgStream.println("Authentication Service: [" +
								this.toString() + "]");
				iDbgStream.println("Authentication Scheme : [" +
								this.authenticationScheme.toString() + "]");
			}
		}
	}

	/**
	/*
	** Methods of module control - To be overriden
	*/

	/**
		Start this module.  In this case, nothing needs to be done.
		@see com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl#boot

		@exception StandardException upon failure to load/boot
		the expected authentication service.
	 */
	 public void boot(boolean create, Properties properties)
	  throws StandardException
	 {
                        // GemStone changes BEGIN
                        // [sb] This service gets booted in FabricDatabase before memstore.
                        //if authentication fails in memstore, somehow .stop() never gets called, 
                        //probably this service is never stopped as it gets booted via JDBCBoot .
                        bootCredentials.clear();
                        // GemStone changes END
			//
			// we expect the Access factory to be available since we're
			// at boot stage.
			//
			store = (AccessFactory)
				Monitor.getServiceModule(this, AccessFactory.MODULE);
			// register to be notified upon db properties changes
			// _only_ if we're on a database context of course :)

			PropertyFactory pf = (PropertyFactory)
				Monitor.getServiceModule(this, com.pivotal.gemfirexd.internal.iapi.reference.Module.PropertyFactory);
			if (pf != null)
				pf.addPropertySetNotification(this);
			
                        // GemStone changes BEGIN
                        if (isPeerAuthenticationService && !(this instanceof NoneAuthenticationServiceImpl) && requireAuthentication(properties)) {
                          String thisClass = this.getClass().getName();
                          properties.put(DistributionConfig.GEMFIRE_PREFIX
                              + DistributionConfig.SECURITY_PEER_AUTH_INIT_NAME, thisClass
                              + factoryMethodForGFEAuth);
                          properties.put(DistributionConfig.GEMFIRE_PREFIX
                              + DistributionConfig.SECURITY_PEER_AUTHENTICATOR_NAME, thisClass
                              + factoryMethodForGFEAuth);

                          //convert 'gemfirexd.security.*' props to GFE's format gemfire.security-*
                          Properties transformed = SecurityUtils.transformGFXDToGemFireProperties(properties);
                          properties.putAll(transformed);
                          isShuttingDown = false;
                        }

                        // cache the boot time credentials here
                        try {
                          Properties credentials = getCredentials(properties,
                              GemFireStore.getMyId(), isPeerAuthenticationService);
                          bootCredentials.putAll(credentials);

                          if (GemFireXDUtils.TraceAuthentication) {
                            SanityManager.DEBUG_PRINT(AuthenticationTrace,
                                "AuthenticationServiceBase: storing boot credentials of size "
                                    + bootCredentials.size());
                          }
                        } catch (GemFireSecurityException ignored) {
                        }

                        this.bootProperties = properties;
                        // GemStone changes END
	 }

	/**
	 * @see com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl#stop
	 */
	public void stop() {
	  //GemStone changes BEGIN
	  //[sb] clearing cached credentials.
	  bootCredentials.clear();
          //GemStone changes END
		// nothing special to be done yet.
	}
	/*
	** Methods of AuthenticationService
	*/

	/**
	 * Authenticate a User inside JBMS.T his is an overload method.
	 *
	 * We're passed-in a Properties object containing user credentials information
	 * (as well as database name if user needs to be validated for a certain
	 * database access).
	 *
	 * @see
	 * com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService#authenticate
	 *
	 *
	 */
	public String /* GemStone change boolean */ authenticate(String databaseName, Properties userInfo) throws java.sql.SQLException
	{

	  // GemStone changes BEGIN
                // Distinguishing between a connection call from the user and that from a
                // member.
                // If the call is from a user, proceed with authentication. If it is from a
                // member, authentication must already
                // have happened at that member's level and so does not need to be performed
                // again.
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement e : stack) {
                  String className = e.getClassName();
                  String methodName = e.getMethodName();
                  if ((className.equals(GfxdConnectionWrapper.class.getName()) && methodName
                      .equals("createConnection"))
                      || (className.equals(GemFireXDUtils.class.getName()) && methodName
                          .equals("createNewInternalConnection"))) {
                    if (GemFireXDUtils.TraceAuthentication) {
                      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                          "Skipping authentication for peer or internal connection");
                    }
                    GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
                        .getInstance();
                    if (observer != null) {
                      observer.memberConnectionAuthenticationSkipped(true);
            
                    }
                    return null;
                  }
                }
                GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
                if (observer != null) {
                  observer.userConnectionAuthenticationSkipped(false);
                }
	    
	 // GemStone changes END
            	  
		if (userInfo == null)
			return "No user/password provided";

		//GemStone changes BEGIN
		//skip boot time local authentication as peer auth would have taken care.
                if (!isPeerAuthenticationService
                    && userInfo.getProperty(GfxdConstants.PROPERTY_BOOT_INDICATOR) != null)
                  return null;
		
                /*(original code) String userName = userInfo.getProperty(Attribute.USERNAME_ATTR);*/
                String userName = userInfo.getProperty(Attribute.USERNAME_ATTR);
                userName = userName == null ? userInfo.getProperty(Attribute.USERNAME_ALT_ATTR) : userName;
                if (userName != null) {
                  try {
                    userName = IdUtil.getUserAuthorizationId(userName);
                  } catch (StandardException se) {
                    throw PublicAPI.wrapStandardException(se);
                  }
                }
		//GemStone changes END
		if ((userName != null) && userName.length() > Limits.DB2_MAX_USERID_LENGTH) {
		// DB2 has limits on length of the user id, so we enforce the same.
		// This used to be error 28000 "Invalid authorization ID", but with v82,
		// DB2 changed the behavior to return a normal "authorization failure
		// occurred" error; so that means just return "false" and the correct
		// exception will be thrown as usual.
			return "User name '" + userName
			    + "' exceeded maximum allowed length "
			    + Limits.DB2_MAX_USERID_LENGTH;
		}

		if (SanityManager.DEBUG)
		{
// GemStone changes BEGIN
			{
			/* if (SanityManager.DEBUG_ON(AuthenticationTrace)) { 
				java.io.PrintWriter iDbgStream =
					SanityManager.GET_DEBUG_STREAM();

				iDbgStream.println(
		        */
// GemStone changes END

				// The following will print the stack trace of the
				// authentication request to the log.  
				//Throwable t = new Throwable();
				//istream.println("Authentication Request Stack trace:");
				//t.printStackTrace(istream.getPrintWriter());
			}
		}
                // GemStone changes BEGIN
		//return this.authenticationScheme.authenticateUser(userName,
                String retval = this.authenticationScheme.authenticateUser(userName,
						  userInfo.getProperty(Attribute.PASSWORD_ATTR),
						  databaseName,
						  userInfo
						 );
                if (GemFireXDUtils.TraceAuthentication) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                      "Authentication request determined user ["
                          + userName + "] " + (retval == null ? " VALID "
                              : " " + retval + " "));
                }
                return retval;
                // GemStone changes END
	}

	/**
	 * Returns a property if it was set at the database or
	 * system level. Treated as SERVICE property by default.
	 *
	 * @return a property string value.
	 **/
	public String getProperty(String key) {

// GemStone changes BEGIN
	  try {
	    String value = PropertyUtil.getServiceProperty(
	        Misc.getMemStoreBootingNoThrow(), key, null);
	    final Properties props;
	    if (value == null && (props = this.bootProperties) != null) {
	      value = props.getProperty(key);
	    }
	    return value;
	  } catch (StandardException se) {
            if (Monitor.reportOn || GemFireXDUtils.TraceAuthentication) {
              SanityManager.DEBUG_PRINT(AuthenticationTrace, "getProperty("
                  + key + ") received standard exception ", se);
            }
            else {
              SanityManager.DEBUG_PRINT(AuthenticationTrace,
                  "AuthenticationServiceBase:getProperty(" + key
                  + ") received standard exception: " + se.getMessage());
            }
	    // do nothing
	    return null;
	  } catch (CancelException ce) {
            if (Monitor.reportOn || GemFireXDUtils.TraceAuthentication) {
              SanityManager.DEBUG_PRINT(AuthenticationTrace, "getProperty("
                  + key + ") received cancel exception ", ce);
            }
            else {
              SanityManager.DEBUG_PRINT(AuthenticationTrace,
                  "AuthenticationServiceBase:getProperty(" + key
                  + ") received cancel exception: " + ce.getMessage());
            }
	    // do nothing
	    return null;
	  }
	  /* (original code)
		String propertyValue = null;
		TransactionController tc = null;

		try {

		  if (store != null)
          {
		        // Get a boot time tc.
            tc = store.getTransaction(
                ContextService.getFactory().getCurrentContextManager());
          }

		  propertyValue =
			PropertyUtil.getServiceProperty(tc,
											key,
											(String) null);
		  if (tc != null) {
			tc.commit();
			tc = null;
		  }

		} catch (StandardException se) {
			// Do nothing and just return
		}

		return propertyValue;
	  */
// GemStone changes END
	}

	public String getDatabaseProperty(String key) {

// GemStone changes BEGIN
	  try {
	    return PropertyUtil.getDatabaseProperty(
	        Misc.getMemStoreBooting(), key);
	  } catch (StandardException se) {
	    // do nothing
	    return null;
	  } catch (CancelException ce) {
	    // do nothing
	    return null;
	  }
	  /* (original code)
		String propertyValue = null;
		TransactionController tc = null;

		try {
		  // Get a boottime tc.
		  ContextService cs =  ContextService.getFactory();
		  ContextManager cm = cs.getCurrentContextManager();
		  if(cm == null) {
		    cm = cs.newContextManager();
		    cs.setCurrentContextManager(cm);
		  }
		  if (store != null)
			tc = store.getTransaction(cm);

		  propertyValue =
			PropertyUtil.getDatabaseProperty(tc, key);

		  if (tc != null) {
			tc.commit();
			tc = null;
		  }

		} catch (StandardException se) {
			// Do nothing and just return
		}

		return propertyValue;
	  */
// GemStone changes END
	}

	public String getSystemProperty(String key) {

		boolean dbOnly = false;
		dbOnly = Boolean.valueOf(
					this.getDatabaseProperty(
							Property.DATABASE_PROPERTIES_ONLY)).booleanValue();

		if (dbOnly)
			return null;

		return PropertyUtil.getSystemProperty(key);
	}

	/*
	** Methods of PropertySetCallback
	*/
	public void init(boolean dbOnly, Dictionary p) {
		// not called yet ...
	}

	/**
	  @see PropertySetCallback#validate
	*/
	public boolean validate(String key, Serializable value, Dictionary p)	{
// GemStone changes BEGIN
	        boolean isUserProp = key.startsWith(com.pivotal.gemfirexd.internal.iapi
                    .reference.Property.USER_PROPERTY_PREFIX)
                   //SQLF:BC
                  || key.startsWith(com.pivotal.gemfirexd.internal.iapi
                                      .reference.Property.SQLF_USER_PROPERTY_PREFIX);
                if (GemFireXDUtils.TraceAuthentication) {
                  if (isUserProp) {
                    SanityManager.DEBUG_PRINT(AuthenticationTrace, key
                        + " recognized as database user & credentials "
                        + "will be stored.");
                  }
                  else {
                    SanityManager.DEBUG_PRINT(AuthenticationTrace, key
                        + " not recognized as a database user & therefore "
                        + "credentials won't be stored in GemFireXD.");
                  }
                }
// GemStone changes END
		return isUserProp;
	}
	/**
	  @see PropertySetCallback#validate
	*/
	public Serviceable apply(String key,Serializable value,Dictionary p)
	{
		return null;
	}
	/**
	  @see PropertySetCallback#map
	  @exception StandardException Thrown on error.
	*/
	public Serializable map(String key, Serializable value, Dictionary p)
		throws StandardException
	{
		// We only care for "gemfirexd.user." property changes
		// at the moment.
		if ( !key.startsWith(com.pivotal.gemfirexd.internal.iapi.reference.Property.USER_PROPERTY_PREFIX)
		    //SQLF:BC
		     && !key.startsWith(com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_USER_PROPERTY_PREFIX)
                   ) return null;
		// We do not encrypt 'gemfirexd.user.<userName>' password if
		// the configured authentication service is LDAP as the
		// same property could be used to store LDAP user full DN (X500).
		// In performing this check we only consider database properties
		// not system, service or application properties.

		String authService =
			(String)p.get(com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_AUTH_PROVIDER);

		if ((authService != null) &&
			 (StringUtil.SQLEqualsIgnoreCase(authService, Constants.AUTHENTICATION_PROVIDER_LDAP)))
			return null;

		// Ok, we can encrypt this password in the db
		String userPassword = (String) value;

		if (userPassword != null) {
			// encrypt (digest) the password
			// the caller will retrieve the new value
		        //SQLF:BC
		        if(key.startsWith(com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_USER_PROPERTY_PREFIX)) {
    			userPassword = encryptPassword(key.substring(Property
    			    .SQLF_USER_PROPERTY_PREFIX.length()), userPassword,
    			    false, false, true);
		        }
		        else {
	                   userPassword = encryptPassword(key.substring(Property
	                          .USER_PROPERTY_PREFIX.length()), userPassword,
	                    false, false, true);
		        }
		}

		return userPassword;
	}


	// Class implementation

	protected final boolean requireAuthentication(Properties properties) {

		//
		// we check if gemfirexd.authentication.required system
		// property is set to true, otherwise we are the authentication
		// service that should be run.
		//
		String requireAuthentication = PropertyUtil.getPropertyFromSet(
					properties,
					com.pivotal.gemfirexd.internal.iapi.reference.Property.REQUIRE_AUTHENTICATION_PARAMETER
														);
		return Boolean.valueOf(requireAuthentication).booleanValue();
	}

	/**
	 * This method encrypts a clear user password using a
	 * Single Hash algorithm such as SHA-1 (SHA equivalent)
	 * (it is a 160 bits digest)
	 *
	 * The digest is returned as an object string.
	 *
	 * @param userName the name of the user
	 * @param plainTxtUserPassword Plain text user password
	 * must be used only outside the product code like ij, tests etc.
	 *
	 * @return encrypted user password (digest) as a String object
	 */
// GemStone changes BEGIN
	static public String encryptPassword(String userName,
	    String plainTxtUserPassword) throws StandardException {
	  return encryptPassword(userName, plainTxtUserPassword, true,
	      false, true);
	}

	static public boolean isEncrypted(String value) {
            return (value.startsWith(ID_PATTERN_NEW_SCHEME_V3) &&
                    value.length() > ID_PATTERN_NEW_SCHEME_V3.length())
                || (value.startsWith(ID_PATTERN_NEW_SCHEME_V2) &&
                    value.length() > ID_PATTERN_NEW_SCHEME_V2.length())
                || (value.startsWith(ID_PATTERN_NEW_SCHEME_V1) &&
                    value.length() > ID_PATTERN_NEW_SCHEME_V1.length());
	}

        //making it static
        public static String encryptUserPassword(String userName,
            String plainTxtUserPassword, boolean forceEncrypt,
            boolean v2Encrypt, boolean v3Encrypt) throws SQLException {
          try {
            return encryptPassword(userName, plainTxtUserPassword,
                forceEncrypt, v2Encrypt, v3Encrypt);
          } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
          }
        }

        //making it static
	static protected String encryptPassword(String userName,
	    String plainTxtUserPassword, boolean forceEncrypt,
	    boolean v2Encrypt, boolean v3Encrypt) throws StandardException
// GemStone changes END
	{
		if (plainTxtUserPassword == null)
			return null;
		
// GemStone changes BEGIN
                if (!forceEncrypt) {
                  if (
                      isEncrypted(plainTxtUserPassword)) {
                    // this is already encrypted once at the originating node & we are
                    // setting here locally.
                    if (GemFireXDUtils.TraceAuthentication) {
                      SanityManager.DEBUG_PRINT(AuthenticationTrace,
                          "Skipping encryption as it must be encrypted from origin - "
                              + plainTxtUserPassword);
                    }
                    return plainTxtUserPassword;
                  }
                }
// GemStone changes END

		MessageDigest algorithm = null;
		String algoName = v3Encrypt ? "SHA-256" : "SHA-1";
		try
		{
			algorithm = MessageDigest.getInstance(algoName);
		} catch (NoSuchAlgorithmException nsae)
		{
		  throw StandardException.newException(SQLState.ENCRYPTION_NOSUCH_ALGORITHM,
		      algoName, "default");
		}

		algorithm.reset();
		byte[] bytePasswd = StringUtil.toHexByte(
		    plainTxtUserPassword, 0, plainTxtUserPassword.length());
// GemStone changes BEGIN
		if (v3Encrypt || v2Encrypt) {
		  // salt with the user name
		  if (userName == null || userName.length() == 0) {
		    userName = "USER";
		  }
		  else {
		    // normalize the userName
		    userName = IdUtil.getUserAuthorizationId(userName);
		  }
		  GemFireXDUtils.updateCipherKeyBytes(bytePasswd,
		      userName.getBytes(ClientSharedData.UTF8));
		}
// GemStone changes END
		algorithm.update(bytePasswd);
		byte[] encryptVal = algorithm.digest();
		String hexString = (v3Encrypt ? ID_PATTERN_NEW_SCHEME_V3
		    : (v2Encrypt ? ID_PATTERN_NEW_SCHEME_V2
		        : ID_PATTERN_NEW_SCHEME_V1)) + StringUtil.toHexString(
		        encryptVal, 0, encryptVal.length);
                if (SanityManager.DEBUG_ON("__PINT__")) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                      " encrypting with v2encrypt=" + v2Encrypt +
                      plainTxtUserPassword + " to " + hexString, new Throwable());
                }
		return (hexString);

	}

    /**
     * Strong Password Substitution (USRSSBPWD).
     *
     * This method generate a password subtitute to authenticate a client
     * which is using a DRDA security mechanism such as SECMEC_USRSSBPWD.
     *
     * Depending how the user is defined in Derby and if BUILTIN
     * is used, the stored password can be in clear-text (system level)
     * or encrypted (hashed - *not decryptable*)) (database level) - If the
     * user has authenticated at the network level via SECMEC_USRSSBPWD, it
     * means we're presented with a password substitute and we need to
     * generate a substitute password coming from the store to compare with
     * the one passed-in.
     *
     * NOTE: A lot of this logic could be shared with the DRDA decryption
     *       and client encryption managers - This will be done _once_
     *       code sharing along with its rules are defined between the
     *       Derby engine, client and network code (PENDING).
     * 
     * Substitution algorithm works as follow:
     *
     * PW_TOKEN = SHA-1(PW, ID)
     * The password (PW) and user name (ID) can be of any length greater
     * than or equal to 1 byte.
     * The client generates a 20-byte password substitute (PW_SUB) as follows:
     * PW_SUB = SHA-1(PW_TOKEN, RDr, RDs, ID, PWSEQs)
     * 
     * w/ (RDs) as the random client seed and (RDr) as the server one.
     * 
     * See PWDSSB - Strong Password Substitution Security Mechanism
     * (DRDA Vol.3 - P.650)
     *
	 * @return a substituted password.
     */
    protected String substitutePassword(
                String userName,
                String password,
                Properties info,
                boolean databaseUser) {

        MessageDigest messageDigest = null;

        // Pattern that is prefixed to the BUILTIN encrypted password
        String ID_PATTERN_NEW_SCHEME = "3b60";

        // PWSEQs's 8-byte value constant - See DRDA Vol 3
        byte SECMEC_USRSSBPWD_PWDSEQS[] = {
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01
                };
        
        // Generated password substitute
        byte[] passwordSubstitute;

        try
        {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException nsae)
        {
            // Ignore as we checked already during service boot-up
        }
        // IMPORTANT NOTE: As the password is stored single-hashed in the
        // database, it is impossible for us to decrypt the password and
        // recompute a substitute to compare with one generated on the source
        // side - Hence, we have to generate a password substitute.
        // In other words, we cannot figure what the original password was -
        // Strong Password Substitution (USRSSBPWD) cannot be supported for
        // targets which can't access or decrypt passwords on their side.
        //
        messageDigest.reset();

        byte[] bytePasswd = null;
        byte[] userBytes = StringUtil.toHexByte(userName, 0, userName.length());

        if (SanityManager.DEBUG)
        {
            // We must have a source and target seed 
            SanityManager.ASSERT(
              ((info.getProperty(Attribute.DRDA_SECTKN_IN) != null) &&
              (info.getProperty(Attribute.DRDA_SECTKN_OUT) != null)), 
                "Unexpected: Requester or server seed not available");
        }

        // Retrieve source (client)  and target 8-byte seeds
        String sourceSeedstr = info.getProperty(Attribute.DRDA_SECTKN_IN);
        String targetSeedstr = info.getProperty(Attribute.DRDA_SECTKN_OUT);

        byte[] sourceSeed_ =
            StringUtil.fromHexString(sourceSeedstr, 0, sourceSeedstr.length());
        byte[] targetSeed_ =
            StringUtil.fromHexString(targetSeedstr, 0, targetSeedstr.length());

        String hexString = null;
        // If user is at the database level, we don't encrypt the password
        // as it is already encrypted (BUILTIN scheme) - we only do the
        // BUILTIN encryption if the user is defined at the system level
        // only - this is required beforehands so that we can do the password
        // substitute generation right afterwards.
        if (!databaseUser)
        {
            bytePasswd = StringUtil.toHexByte(password, 0, password.length());
            messageDigest.update(bytePasswd);
            byte[] encryptVal = messageDigest.digest();
            hexString = ID_PATTERN_NEW_SCHEME +
                StringUtil.toHexString(encryptVal, 0, encryptVal.length);
        }
        else
            // Already encrypted from the database store
            hexString = password;

        // Generate the password substitute now

        // Generate some 20-byte password token
        messageDigest.update(userBytes);
        messageDigest.update(
                StringUtil.toHexByte(hexString, 0, hexString.length()));
        byte[] passwordToken = messageDigest.digest();
        
        // Now we generate the 20-byte password substitute
        messageDigest.update(passwordToken);
        messageDigest.update(targetSeed_);
        messageDigest.update(sourceSeed_);
        messageDigest.update(userBytes);
        messageDigest.update(SECMEC_USRSSBPWD_PWDSEQS);

        passwordSubstitute = messageDigest.digest();

        return StringUtil.toHexString(passwordSubstitute, 0,
                                      passwordSubstitute.length);
    }

    // GemStone changes BEGIN
    
    /**
     * Capture the p2p authentication service, so that its always available for servicing
     * gfe layer, irrespective of the VM state. 
     */
    public static void setPeerAuthenticationService(AuthenticationServiceBase service) {
      peerAuthenticationService = service;
    }

    public static void setIsShuttingDown(boolean isClosing) {
        if (peerAuthenticationService != null) {
          if (SanityManager.DEBUG) {
            if(GemFireXDUtils.TraceAuthentication) {
              SanityManager.DEBUG_PRINT(SecurityLogWriter.SECURITY_PREFIX
                  + "warning:" + GfxdConstants.TRACE_AUTHENTICATION, " Shutting down authentication module. ");
            }
          }
          peerAuthenticationService.isShuttingDown = isClosing;
        }
    }
    /*
     * Basically a post step of GemFireStore boot. 
     * 1. Refesh the Authentication service with store 
     * 2. add local auth service to notification list.
     */
    public static void refreshAuthenticationServices(FabricDatabase serviceModule,
        AccessFactory accessfactory, PropertyFactory propertyfactory, Properties startParams)
        throws StandardException {
  
      // update Peer Auth Service
      final AuthenticationServiceBase peerAuthService = peerAuthenticationService;

      peerAuthService.store = accessfactory;
      peerAuthService.securitylogger = Misc.getGemFireCache()
          .getSecurityLoggerI18n();
  
      // update Local Auth Service
      AuthenticationServiceBase localAuthService = (AuthenticationServiceBase)Monitor
          .findServiceModule(serviceModule, MODULE, GfxdConstants.AUTHENTICATION_SERVICE);

      propertyfactory.addPropertySetNotification(localAuthService);
      localAuthService.store = accessfactory;
      localAuthService.securitylogger = peerAuthService.securitylogger;
  
      String authStatus = null;
      try {
        authStatus = peerAuthService.authenticate(Attribute.GFXD_DBNAME,
            startParams);
      } catch (SQLException cause) {
        throw StandardException.newException(SQLState.NET_CONNECT_AUTH_FAILED,
            cause, "Authentication failed with exception " + cause);
      }
  
      if (authStatus != null) {
        String userName = startParams.getProperty(Attribute.USERNAME_ATTR);
        userName = userName == null ? startParams.getProperty(Attribute.USERNAME_ALT_ATTR) : userName;
        
        /*  
         * If user is not booted with database user, we can promote it as a system property so that
         * nobody can change the definition via create_user(...).
         * 
         * see #44641, whereby db user was promoted to system user earlier.
         * 
         * GFXD 1.1 now, we already promoted to system user definition. Time to clear it, 
         * if database user.
         */
        String userDefinition = PropertyUtil
            .getSystemProperty(SecurityUtils.SYSTEM_USER_KEY_PROPERTY);
        if (userDefinition != null
            && PropertyUtil.getSystemProperty(userDefinition) == null) {
          PropertyUtil.clearSystemProperty(userDefinition);
          PropertyUtil
              .clearSystemProperty(SecurityUtils.SYSTEM_USER_KEY_PROPERTY);
        }
        
        if (peerAuthService.securitylogger.infoEnabled()) {
          peerAuthService.securitylogger.convertToLogWriter().info(
              "Second phase authentication failed for user '" + userName
                  + "': " + authStatus);
        }
        throw StandardException.newException(SQLState.NET_CONNECT_AUTH_FAILED,
            new AuthenticationFailedException("Authentication failed for "
                + GemFireStore.getMyId() + ": " + authStatus), authStatus);
      }
    }

    public static void cleanupOnError(FabricDatabase fabricDatabase,
        GemFireStore memStore, PropertyFactory pf) {
      String userDefinition = PropertyUtil
          .getSystemProperty(SecurityUtils.SYSTEM_USER_KEY_PROPERTY);
      if (userDefinition != null) {
        PropertyUtil.clearSystemProperty(userDefinition);
        PropertyUtil.clearSystemProperty(SecurityUtils.SYSTEM_USER_KEY_PROPERTY);
      }
    }
    
    public static AuthenticationServiceBase getPeerAuthenticationService() {
  
      AuthenticationServiceBase peerAuthService = peerAuthenticationService;
  
      if (peerAuthService == null) {

        NoneAuthenticationServiceImpl noneAuthService =
            new NoneAuthenticationServiceImpl();
        if (SanityManager.DEBUG) {
          if(GemFireXDUtils.TraceAuthentication) {
            SanityManager.DEBUG_PRINT(SecurityLogWriter.SECURITY_PREFIX
                + "warning:" + GfxdConstants.TRACE_AUTHENTICATION, " Using NONE authentication ... ");
          }
        }

        peerAuthService = noneAuthService;
        peerAuthService.isPeerAuthenticationService = true;
      }

      if (SanityManager.ASSERT) {
        
          if (!(peerAuthService != null
              && Authenticator.class.isAssignableFrom(peerAuthService.getClass()) 
              && AuthInitialize.class
                .isAssignableFrom(peerAuthService.getClass()))) {
            SanityManager.THROWASSERT("AuthenticationService ("
                + peerAuthService.getClass()
                + ") is not compatible with GemFire interfaces ");
    
          }
      }
  
      return peerAuthService;
    }
    
    /**
     * {@link AuthInitialize#getCredentials(Properties, DistributedMember, boolean)}
     */
    @Override
    public Properties getCredentials(Properties securityProps,
        DistributedMember server, boolean isPeer)
        throws AuthenticationFailedException {
  
      assert isPeer == isPeerAuthenticationService: this
          + " service shouldn't be used for something else other than peer authentication ";
      
      Properties credentials = null;
      // convert GFE's security-* to gemfirexd.security.*
      if (authenticationScheme instanceof CredentialInitializer) {
  
        credentials = SecurityUtils
            .transformGemFireToGFXDProperties(securityProps);

        if (securitylogger != null && securitylogger.fineEnabled()) {
          StringPrintWriter spw = new StringPrintWriter();
          GemFireXDUtils.dumpProperties(securityProps,
              "transforming GemFire Properties ",
              GfxdConstants.TRACE_AUTHENTICATION, true, spw);
          GemFireXDUtils.dumpProperties(credentials, " to GFXD Properties ",
              GfxdConstants.TRACE_AUTHENTICATION, true, spw);

          securitylogger.fine(spw.toString());
        }

        try {
  
          credentials = ((CredentialInitializer)authenticationScheme)
              .getCredentials(credentials);
          
        } catch (Exception cause) {
          securitylogger
              .warning(
                  LocalizedStrings.ONE_ARG,
                  new Object[] { "Exception in acquiring credentials with authentication scheme: "
                      + authenticationScheme }, cause);
          throw new AuthenticationFailedException(
              "Error getting credentials: " + cause, cause);
        }
  
      }
      else {
        if (securitylogger != null) {
          securitylogger.warning(
              LocalizedStrings.Gfxd_AUTHENTICATION__NO_CREDENTIAL_INITIALIZER,
              new String[] { CredentialInitializer.class.getCanonicalName(),
                  SecurityUtils.GFXD_SEC_PREFIX,
                  UserAuthenticator.class.getCanonicalName() });
        }
  
        credentials = SecurityUtils.trimOffGemFireProperties(securityProps);
      }

      if(credentials == null) {
        securitylogger.warning(LocalizedStrings.ONE_ARG, new Object[] {
            "Couldn't locate credentials with authentication scheme: " +
            authenticationScheme + securityProps});
        throw new AuthenticationFailedException(
            "Null credentials not allowed");
      }

      GemFireXDUtils.dumpProperties(credentials,
          "authentication credentials",
          AuthenticationTrace, GemFireXDUtils.TraceAuthentication, null);
      return credentials;
    }

    /**
     * {@link AuthInitialize#init(LogWriter, LogWriter)}
     */
    @Override
    public void init(LogWriter systemLogger, LogWriter securityLogger)
        throws AuthenticationFailedException {
      if(this.securitylogger == null) {
        this.securitylogger = securityLogger.convertToLogWriterI18n();
      }
    }
  
    /**
     * {@link AuthInitialize#close()}
     */
    @Override
    public void close() {
    }
    
    
    /**
     * {@link Authenticator#authenticate(Properties, DistributedMember)}
     */
    @Override
    public Principal authenticate(Properties props, DistributedMember member)
        throws AuthenticationFailedException {
      
      if(isShuttingDown) {
        if (GemFireXDUtils.TraceAuthentication) {
          SanityManager.DEBUG_PRINT(SecurityLogWriter.SECURITY_PREFIX
              + "warning:" + GfxdConstants.TRACE_AUTHENTICATION,
              "VM is shutting down, so skip authentication");
        }
        return null;
      }

      String authStatus = null;
      try {
        GemFireXDUtils.dumpProperties(props, "connection authentication for " + member + " with ",
            GfxdConstants.TRACE_AUTHENTICATION,
            GemFireXDUtils.TraceAuthentication, null);
        
        authStatus = this.authenticate(Attribute.GFXD_DBNAME, props);

      } catch (Throwable cause) {
          // if local authentication, then don't log anything.
          StringBuilder sb = new StringBuilder(
              "Exception in getting credentials of user for ");
          sb.append(member).append(".");
          if (this.authenticationScheme instanceof BasicAuthenticationServiceImpl) {
            sb.append(GemFireXDUtils.dumpProperties(props, " Properties : ",
                GfxdConstants.TRACE_AUTHENTICATION, true, new StringPrintWriter())
                .toString());
          }
          if (securitylogger != null) {
            sb.append(". Exception : ").append(cause);
            securitylogger.warning(LocalizedStrings.ONE_ARG, sb.toString(), cause);
          }
          else {
            sb.append(". Exception : ").append(cause);
            SanityManager.DEBUG_PRINT(SecurityLogWriter.SECURITY_PREFIX
                + "warning:" + GfxdConstants.TRACE_AUTHENTICATION, sb.toString(),
                cause);
          }
          Misc.throwIfCacheNotClosed(new AuthenticationFailedException(
              "Authentication failed for " + member + " with " + cause, cause));
      }

      if (authStatus != null) {
        
        String val = "undefined value" ;
        String userName = props.getProperty(Attribute.USERNAME_ATTR);
        userName = userName == null ? props.getProperty(Attribute.USERNAME_ALT_ATTR) : userName;
        if (userName != null) {
          try {
            userName = IdUtil.getUserAuthorizationId(userName);
          } catch (StandardException se) {
            throw new AuthenticationFailedException(se.getMessage());
          }
        }
        if(authenticationScheme instanceof BasicAuthenticationServiceImpl) {
          val = getSystemProperty(Property.USER_PROPERTY_PREFIX + userName);
          //SQLF:BC
          if (val == null) {
            val = getSystemProperty(Property.SQLF_USER_PROPERTY_PREFIX + userName);
          }
        }
        if (val == null && member == null) {
          // else don't throw as member == null means local validation & val == null means non-system user.
          //revalidation will happen in reboot(..) & we will throw then.
          if (GemFireXDUtils.TraceAuthentication) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                "user not found. initiating second phase authentication for "
                    + userName);
          }
        }
        else {
          StringBuilder sb = new StringBuilder();
          sb.append("Rejecting credentials of user '").append(userName)
              .append("' for ").append(member).append(": ")
              .append(authStatus).append(".");
          if(this.authenticationScheme instanceof BasicAuthenticationServiceImpl) {
            sb.append(" User Definition in this member is ").append(val);
          }
          if(securitylogger != null) {
            securitylogger.warning(LocalizedStrings.ONE_ARG, sb.toString());
          }
          else {
            SanityManager.DEBUG_PRINT(SecurityLogWriter.SECURITY_PREFIX
                + "warning:" + GfxdConstants.TRACE_AUTHENTICATION, sb.toString());
          }
          throw new AuthenticationFailedException(authStatus);
        }
      }

      //returning NULL principal, so that there is no authorization happen at GFE layer.
      return null;
    }

    /**
     * {@link Authenticator#init(Properties, LogWriter, LogWriter)}
     */
    @Override
    public void init(Properties securityProps, LogWriter systemLogger,
        LogWriter securityLogger) throws AuthenticationFailedException {
      // nothing to be done
    }

    public UserAuthenticator getAuthenticationScheme() {
      return this.authenticationScheme;
    }

    public Properties getBootCredentials() {
      GemFireXDUtils
          .dumpProperties(
              bootCredentials,
              "AuthenticationServiceBase: returning cached boot credentials",
              GfxdConstants.TRACE_AUTHENTICATION,
              GemFireXDUtils.TraceAuthentication, null);
  
      return bootCredentials;
    }
    
    public boolean checkAndSetSchemeSupported(String identifier, Properties properties, String scheme) {
        if (SanityManager.DEBUG) {
          if (Monitor.reportOn) {
            SanityManager.DEBUG_PRINT(AuthenticationTrace,
                "Checking support for scheme " + scheme + " with service "
                    + identifier);
          }
        }

        String authenticationProvider = PropertyUtil.getPropertyFromSet(properties,
            Attribute.AUTH_PROVIDER);
    
        if (authenticationProvider == null) {
          authenticationProvider = PropertyUtil
              .getPropertyFromSet(
                  properties,
                  com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_AUTH_PROVIDER);
        }

        if (authenticationProvider == null) {
          authenticationProvider = PropertyUtil
              .getPropertyFromSet(
                  properties,
                  com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_AUTH_PROVIDER);
        }
        
        String peerAuthenticationProvider = PropertyUtil
            .getPropertyFromSet(
                properties,
                Attribute.SERVER_AUTH_PROVIDER);
        if(peerAuthenticationProvider == null) {
            peerAuthenticationProvider = PropertyUtil
                .getPropertyFromSet(
                    properties,
                    com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_SERVER_AUTH_PROVIDER);
        }

        if (peerAuthenticationProvider == null) {
          peerAuthenticationProvider = PropertyUtil
              .getPropertyFromSet(
                  properties,
                  com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_SERVER_AUTH_PROVIDER);
        }
        
        if (SanityManager.DEBUG) {
          if (Monitor.reportOn) {
            SanityManager.DEBUG_PRINT(AuthenticationTrace, identifier
                + " authenticationProvider=" + authenticationProvider
                + " peerAuthenticationProvider=" + peerAuthenticationProvider);
          }
        }

        if((authenticationProvider != null && authenticationProvider.length() > 0) || (peerAuthenticationProvider != null && peerAuthenticationProvider.length() > 0) ) {
            properties
                .setProperty(
                    com.pivotal.gemfirexd.internal.iapi.reference.Property.REQUIRE_AUTHENTICATION_PARAMETER,
                    Boolean.TRUE.toString());
        }
        else {
          properties
          .setProperty(
              com.pivotal.gemfirexd.internal.iapi.reference.Property.REQUIRE_AUTHENTICATION_PARAMETER,
              Boolean.FALSE.toString());
        }
        
        //if peer authentication is not set but authentication is set.
        if ((peerAuthenticationProvider == null || peerAuthenticationProvider
            .length() == 0)
            && (authenticationProvider != null && authenticationProvider.length() > 0)) {

          if (SanityManager.DEBUG) {
            if (Monitor.reportOn) {
              SanityManager.DEBUG_PRINT(AuthenticationTrace, "inheriting "
                  + GfxdConstants.PEER_AUTHENTICATION_SERVICE + " from "
                  + GfxdConstants.AUTHENTICATION_SERVICE + " service to " + authenticationProvider);
            }
          }
          
          properties
              .setProperty(
                  Attribute.SERVER_AUTH_PROVIDER,
                  authenticationProvider);
          
          peerAuthenticationProvider = authenticationProvider;
        }

        // enable authorization by default
        if (authenticationProvider != null
            && authenticationProvider.length() > 0
            && !StringUtil.SQLEqualsIgnoreCase(authenticationProvider, "NONE")
            && PropertyUtil.getPropertyFromSet(properties,
                com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION) == null
            && PropertyUtil.getSystemProperty(
                com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION) == null) {
          SanityManager.DEBUG_PRINT(AuthenticationTrace,
              "Enabling authorization for auth provider "
                  + authenticationProvider);
          PropertyUtil.setSystemProperty(
              com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION, "true");
        }

        //if service boot is happening for authentication then simply return whether we support this or not.
        if (identifier.equals(GfxdConstants.AUTHENTICATION_SERVICE)) {
    
            if (authenticationProvider == null
                || authenticationProvider.length() <= 0
                || (!(StringUtil.SQLEqualsIgnoreCase(authenticationProvider, scheme)))) {
              if (SanityManager.DEBUG) {
                if (Monitor.reportOn) {
                  SanityManager.DEBUG_PRINT(AuthenticationTrace,
                      GfxdConstants.AUTHENTICATION_SERVICE + " is NOT enabled with "
                          + scheme + " auth provider is " + authenticationProvider);
                }
              }
              return false;
            }
            else {
              if (SanityManager.DEBUG) {
                if (Monitor.reportOn) {
                  SanityManager.DEBUG_PRINT(AuthenticationTrace,
                      GfxdConstants.AUTHENTICATION_SERVICE + " is enabled with "
                          + scheme);
                }
              }
      
              return true;
            }
    
        }
        
        //if peer.auth service is being booted, check whether we support this or not.
        else if (identifier.equals(GfxdConstants.PEER_AUTHENTICATION_SERVICE)) {
    
            isPeerAuthenticationService = true;
            if (peerAuthenticationProvider == null
                || peerAuthenticationProvider.length() <= 0
                || (!(StringUtil.SQLEqualsIgnoreCase(peerAuthenticationProvider,
                    scheme)))) {
              return false;
            }
            else {
              if (SanityManager.DEBUG) {
                if (Monitor.reportOn) {
                  SanityManager.DEBUG_PRINT(AuthenticationTrace,
                      GfxdConstants.PEER_AUTHENTICATION_SERVICE + " is enabled with "
                          + scheme);
                }
              }
              return true;
            }
    
        }
    
        return false;
    }
    
    /*
     * called by FabricAPI only & therefore encryptPassword is with true.
     */
    public static final boolean isSecurityProperty(String key, String value,
        Properties promoteToSystemProperties) throws SQLException {
      
      boolean retval = false;
      if (key.equalsIgnoreCase(Property.REQUIRE_AUTHENTICATION_PARAMETER)
          || key.equalsIgnoreCase(Property.GFXD_AUTH_PROVIDER)
          || key.equalsIgnoreCase(Property.GFXD_SERVER_AUTH_PROVIDER)
          || key.equalsIgnoreCase(Property.SQLF_AUTH_PROVIDER)
          || key.equalsIgnoreCase(Property.SQLF_SERVER_AUTH_PROVIDER)) {
        retval = false;
      }
  
      if (key.equalsIgnoreCase(com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION)
          || key.equalsIgnoreCase(com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE)
          || key.equalsIgnoreCase(com.pivotal.gemfirexd.Property.AUTHZ_READ_ONLY_ACCESS_USERS)
          || key.equalsIgnoreCase(com.pivotal.gemfirexd.Property.AUTHZ_FULL_ACCESS_USERS)
          || key.equalsIgnoreCase(Property.DATABASE_PROPERTIES_ONLY)) {
        retval = true;
      }
  
      if (key.toLowerCase().startsWith(Property.USER_PROPERTY_PREFIX)) {
        retval = false;
        // skip if already encrypted.
        if (!isEncrypted(value)) {
          value = encryptUserPassword(
              key.substring(Property.USER_PROPERTY_PREFIX.length()), value, true,
              false, true);
        }
      }
      //SQLF:BC
      else if (key.toLowerCase().startsWith(Property.SQLF_USER_PROPERTY_PREFIX)) {
        retval = false;
        // skip if already encrypted.
        if (!isEncrypted(value)) {
          value = encryptUserPassword(
              key.substring(Property.SQLF_USER_PROPERTY_PREFIX.length()), value,
              true, false, true);
        }
      }
      // for the time being support auth-provider and server-auth-provider
      // in the property list. 
      // TODO: sb: need to change Property.AUTHENTICATION_PROVIDER itself to these.
      else if (key.equalsIgnoreCase(Attribute.AUTH_PROVIDER)) {
        promoteToSystemProperties.setProperty(
            Property.REQUIRE_AUTHENTICATION_PARAMETER, "true");
        key = Property.GFXD_AUTH_PROVIDER;
        retval = false;
      }
      else if (key.equalsIgnoreCase(Attribute.SERVER_AUTH_PROVIDER)) {
        promoteToSystemProperties.setProperty(
            Property.REQUIRE_AUTHENTICATION_PARAMETER, "true");
        key = Property.GFXD_SERVER_AUTH_PROVIDER;
        retval = false;
      }
      
      if(retval) {
        promoteToSystemProperties.setProperty(key, value);
      }
      
      return retval;
    }
    
    public static final String maskedvalue = "******";
    public static final String val = "_!_-p-h-_!_";
    
    public static final Object maskProperty(String key, Object value) {
      //ooh! creating garbage, but needed because of security & this function is limitedly used.
      key = key.toLowerCase();
      
      if (key.startsWith(Property.PROPERTY_RUNTIME_PREFIX) ) {
        return val;
      }
      
      if (key.startsWith(Property.USER_PROPERTY_PREFIX)
          //SQLF:BC
          || key.startsWith(Property.SQLF_USER_PROPERTY_PREFIX)
          || key.contains(Attribute.PASSWORD_ATTR)
          || key.equals(com.pivotal.gemfirexd.Property.AUTH_LDAP_SEARCH_PW)
          || key.equals(Context.SECURITY_CREDENTIALS)) {
        return maskedvalue;
      }
      
      if ((key.contains("authenticator") || key.contains("auth-init"))
          && value != null) {
  
        if (NoneAuthenticationServiceImpl.AUTHFACTORYMETHOD.equals(value))
          return "NONE";
  
        if (BasicAuthenticationServiceImpl.AUTHFACTORYMETHOD.equals(value))
          return Constants.AUTHENTICATION_PROVIDER_BUILTIN;
  
        if (JNDIAuthenticationService.AUTHFACTORYMETHOD.equals(value))
          return Constants.AUTHENTICATION_PROVIDER_LDAP;
      }
      
      return value;
    }

    public static final boolean isAuthenticationBUILTIN(
        AuthenticationService[] otherService) {
      FabricDatabase database = Misc.getMemStoreBooting().getDatabase();
      AuthenticationService authService = (AuthenticationServiceBase)Monitor
          .findServiceModule(database, MODULE,
              GfxdConstants.PEER_AUTHENTICATION_SERVICE);

      if (SanityManager.ASSERT) {
        SanityManager.ASSERT(authService != null,
            " couldn't find authentication service");
      }
      if (authService instanceof BasicAuthenticationServiceImpl) {
        return true;
      }
      // check AUTHENTICATION_SERVICE if PEER_AUTHENTICATION_SERVICE is absent
      else if (authService == null
          || authService instanceof NoneAuthenticationServiceImpl) {
        authService = (AuthenticationServiceBase)Monitor.findServiceModule(
            database, MODULE, GfxdConstants.AUTHENTICATION_SERVICE);
        if (authService instanceof BasicAuthenticationServiceImpl) {
          return true;
        }
      }
      if (otherService != null) {
        otherService[0] = authService;
      }
      return false;
    }

    public static final void validateUserPassword(String user, String password,
        boolean passwordOptional) throws SQLException {
      
      StandardException se = null;
  
      if (user == null) {
        se = StandardException.newException(SQLState.CONNECT_USERID_ISNULL);
      }
      else if (!passwordOptional && password == null) {
        se = StandardException.newException(SQLState.CONNECT_PASSWORD_ISNULL);
      }
      else if (PropertyUtil.whereSet(user, null) == PropertyUtil.SET_IN_JVM) {
        se = StandardException.newException(SQLState.AUTH_CANNOT_OVERWRITE_USER_DEFINTION, user);
      }
      
      if(se != null) {
        throw PublicAPI.wrapStandardException(se);
      }
      
    }

    // GemStone changes END
}
