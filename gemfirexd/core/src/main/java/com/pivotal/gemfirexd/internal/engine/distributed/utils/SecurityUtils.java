/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.distributed.utils;

import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Properties;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.SecurityLogWriter;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedSQLException;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;

/**
 * Various security related utility methods.
 * 
 * @author soubhikc
 */
public class SecurityUtils {

  public final static String GFXD_SEC_PREFIX = GfxdConstants.GFXD_PREFIX
      + GfxdConstants.GFXD_SEC_PREFIX;

  public final static String GFE_SEC_PREFIX = DistributionConfig.GEMFIRE_PREFIX
      + DistributionConfig.SECURITY_PREFIX_NAME;

  /**
   * What ever connection properties user have passed with 'gemfirexd.security.*' prefixed, we 
   * loop through GFE and give them in UserAuthenticator.authenticate(..) callback.
   * 
   * @param gfxdSecurityProps
   * @return
   */
  public static Properties transformGFXDToGemFireProperties(
      Properties gfxdSecurityProps) {

    if (gfxdSecurityProps == null) {
      return gfxdSecurityProps;
    }

    Properties gfeProps = new Properties();

    for (Enumeration<?> e = gfxdSecurityProps.propertyNames(); e
        .hasMoreElements();) {
      String key = (String)e.nextElement();
      if (key.startsWith(GFXD_SEC_PREFIX)) {
        gfeProps.put(GFE_SEC_PREFIX + key.substring(GFXD_SEC_PREFIX.length()),
            gfxdSecurityProps.getProperty(key));
      }

      // special keys (user/password) that generally comes through connection
      // URL.
      else if (key.equals(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR)
          || key.equals(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR)
          || key.equals(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR)) {
        gfeProps.put(GFE_SEC_PREFIX + key, gfxdSecurityProps.getProperty(key));

      }
      else {
        Object val = gfxdSecurityProps.getProperty(key);
        if (val != null) {
          gfeProps.put(key, val);
        }
      }
    }

    return gfeProps;
  }

  /**
   * Converts GemXD security properties so that those can be directly passed during
   * auto reconnect (for example converts "user" to "security-user").
   * @param gfxdProps
   * @return
   */
  public static Properties transformCredentialsForAutoReconnect(
      Properties gfxdProps) {

    if (gfxdProps == null) {
      return gfxdProps;
    }

    Properties gfeProps = new Properties();

    for (Enumeration<?> e = gfxdProps.propertyNames(); e
        .hasMoreElements();) {
      String key = (String)e.nextElement();
      if (key.startsWith(GFXD_SEC_PREFIX)) {
        gfeProps.put(DistributionConfig.SECURITY_PREFIX_NAME + key.substring(GFXD_SEC_PREFIX.length()),
            gfxdProps.getProperty(key));
      }

      // special keys (user/password) that generally comes through connection
      // URL.
      else if (key.equals(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR)
          || key.equals(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR)
          || key.equals(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR)) {
        gfeProps.put(DistributionConfig.SECURITY_PREFIX_NAME + key, gfxdProps.getProperty(key));

      }
    }

    return gfeProps;
  }

  /**
   * Here we don't filter out gemfire.security-peer-* GFE properties which are
   * not user supplied instead part of GFXD to GFE security configuration.
   * 
   * TODO might need to filter out gemfire.security-peer-auth-init /
   * peer-authenticator props
   * 
   * @param gfeSecurityProps
   * @return
   */
  public static Properties transformGemFireToGFXDProperties(
      Properties gfeSecurityProps) {

    if (gfeSecurityProps == null) {
      return gfeSecurityProps;
    }

    Properties gfxdProps = new Properties();

    for (Enumeration<?> e = gfeSecurityProps.propertyNames(); e
        .hasMoreElements();) {
      String key = (String)e.nextElement();
      if (key.startsWith(DistributionConfig.SECURITY_PREFIX_NAME)) {
        gfxdProps.put(GFXD_SEC_PREFIX
            + key.substring(DistributionConfig.SECURITY_PREFIX_NAME.length()),
            gfeSecurityProps.getProperty(key));
      }

      else if (key.startsWith(GFE_SEC_PREFIX)) {
        gfxdProps.put(GFXD_SEC_PREFIX + key.substring(GFE_SEC_PREFIX.length()),
            gfeSecurityProps.getProperty(key));
      }
      else {
        String val = gfeSecurityProps.getProperty(key);
        if (val != null) {
          gfxdProps.setProperty(key, val);
        }
      }
    }

    return gfxdProps;
  }
  
  /**
   * Gets called if for some reason user haven't implemented CredentialInitializer interface.
   * We simply trim off 'gemfire.security-' properties prefix and consider the properties to 
   * be enough for authenticate to complete.
   * 
   * @param inProperties
   * @return
   */
  public static Properties trimOffGemFireProperties(Properties inProperties) {
      Properties outProperties = new Properties();
      
      assert (inProperties != null) : "GFE wouldn't have called with null props.";

      for (Enumeration<?> e = inProperties.propertyNames(); e
          .hasMoreElements();) {
        String key = (String)e.nextElement();
        if (key.startsWith(DistributionConfig.SECURITY_PREFIX_NAME)) {
          outProperties.put(key.substring(DistributionConfig.SECURITY_PREFIX_NAME.length()),
              inProperties.getProperty(key));
        }

        else if (key.startsWith(GFE_SEC_PREFIX)) {
          outProperties.put(key.substring(GFE_SEC_PREFIX.length()),
              inProperties.getProperty(key));
        }
        else {
          String val = inProperties.getProperty(key);
          if (val != null) outProperties.put(key, val);
        }
      }

      return outProperties;
  }

  public static final String SYSTEM_USER_KEY_PROPERTY = "gemfirexd.__rt.system-user-id";

  public static final void processSystemUserDefinition(Properties properties)
      throws StandardException {
    String value = null;
    String key = null;
    for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
      key = (String)e.nextElement();
      //SQLF:BC
      boolean sqlfireUserProp = key.startsWith(com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_USER_PROPERTY_PREFIX);
      if (key.startsWith(com.pivotal.gemfirexd.internal.iapi.reference.Property.USER_PROPERTY_PREFIX)
       || sqlfireUserProp) {
        value = properties.getProperty(key);
        String origKey = key;
        key = IdUtil.getDBUserId(key, true);
        if (!AuthenticationServiceBase.isEncrypted(value)) {
          SanityManager.DEBUG_PRINT(SecurityLogWriter.SECURITY_PREFIX
              + "warning:" + GfxdConstants.TRACE_AUTHENTICATION,
              "SecurityUtils: password not encrypted in user definition " + key
                  + ". Encrypting... ");
          value = AuthenticationServiceBase.encryptPassword(key
              .substring((sqlfireUserProp ? Property.SQLF_USER_PROPERTY_PREFIX
                  .length() : Property.USER_PROPERTY_PREFIX.length())), value);
        }
        properties.setProperty(key, value);
        if (!key.equals(origKey)) {
          //properties.setProperty(origKey, value);
          properties.remove(origKey);
        }
        PropertyUtil.setSystemProperty(SYSTEM_USER_KEY_PROPERTY, key);
        /* don't promote as now AuthService honors connection properties. #44641.
         * 
         * GFXD 1.1 now promotes the user definition to system property before authentication 
         * because there is a window where Locator can exchange VIEW info and authentication
         * fail to find the user definition.
         * On Auth failure, cleanupOnError will unset the user definition. Also, if the 
         * system is booted with database user, it is promoted here temporarily before
         * Dictionary syncs up and we are in a state to know whether it is a database user.
         * In such case, it will be unset on successfull bootstrap.
         */ 
        PropertyUtil.setSystemProperty(key, value);
      }
    }
  }
  
  public static void prepareUserDefinitionForBuiltInScheme(Properties properties)
      throws StandardException {
    
    processSystemUserDefinition(properties);

    String userAtt = properties.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    userAtt = userAtt == null ? properties.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userAtt;
    String pwdAtt = properties.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
    if (userAtt != null) {
      userAtt = IdUtil.getUserAuthorizationId(userAtt);
    }

    try {
      
      AuthenticationServiceBase.validateUserPassword(userAtt, pwdAtt, true);
      
    } catch (SQLException sqle) {
      assert sqle instanceof EmbedSQLException;
      assert ((EmbedSQLException)sqle).isSimpleWrapper();
      Throwable wrapped = sqle.getCause();
      if (wrapped instanceof StandardException)
        throw (StandardException)wrapped;
    }
    
    String value = PropertyUtil
        .getPropertyFromSet(
            properties,
            com.pivotal.gemfirexd.internal.iapi.reference.Property.USER_PROPERTY_PREFIX
                + userAtt);

    //SQLF:BC
    if (value == null) {
      value = PropertyUtil
          .getPropertyFromSet(
              properties,
              com.pivotal.gemfirexd.internal.iapi.reference.Property.SQLF_USER_PROPERTY_PREFIX
                  + userAtt);
    }
    
    if (value == null) {
      SanityManager.DEBUG_PRINT(
          "warning:" + GfxdConstants.TRACE_AUTHENTICATION,
          " User definition for " + userAtt + " is missing.");
    }
  }
  
  public static Properties getCredentials(Properties securityProps) {
    if (securityProps == null)
      return null;
    
    String userName = securityProps.getProperty(SecurityUtils.GFXD_SEC_PREFIX
        + Attribute.USERNAME_ATTR);
    boolean isUserNameAttribute = false;
    if(userName == null) {
      userName = securityProps.getProperty(SecurityUtils.GFXD_SEC_PREFIX
          + Attribute.USERNAME_ALT_ATTR);
      if(userName != null) {
        isUserNameAttribute = true;
      }
    }

    String password = securityProps.getProperty(SecurityUtils.GFXD_SEC_PREFIX
        + Attribute.PASSWORD_ATTR);

    // validation exception here causes long hangs and other kinds
    // of problem; let locator reject the credentials with
    // proper checking
    //validateUserPassword(userName, password, false);

    if (userName != null) {
      securityProps.put(isUserNameAttribute ? Attribute.USERNAME_ALT_ATTR
          : Attribute.USERNAME_ATTR, userName);
    }
    if (password != null) {
      securityProps.put(Attribute.PASSWORD_ATTR, password);
    }
    
    return securityProps;
  }

}
