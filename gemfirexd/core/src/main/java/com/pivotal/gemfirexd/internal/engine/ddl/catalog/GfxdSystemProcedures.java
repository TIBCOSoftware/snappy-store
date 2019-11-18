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

package com.pivotal.gemfirexd.internal.engine.ddl.catalog;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.control.RebalanceOperation;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.persistence.query.CloseableIterator;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.ColumnTableEntry;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.catalog.AliasInfo;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.catalog.SystemProcedures;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLQueueEntry;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.CallbackProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.AbstractGfxdReplayableMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction.QueryCancelFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GetLeadNodeInfoAsStringMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.SecurityUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.CustomRowsResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.db.PropertyInfo;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.ConnectionUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.*;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialBlob;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialClob;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.LDAPAuthenticationSchemeImpl;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.conn.GenericLanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.execute.JarUtil;
import com.pivotal.gemfirexd.internal.impl.store.raw.data.GfxdJarMessage;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.load.Import;
import io.snappydata.thrift.BucketOwners;
import io.snappydata.thrift.CatalogMetadataDetails;
import io.snappydata.thrift.CatalogMetadataRequest;
import io.snappydata.thrift.CatalogTableObject;
import io.snappydata.thrift.ServerType;
import io.snappydata.thrift.internal.ClientBlob;
import io.snappydata.thrift.snappydataConstants;
import org.apache.log4j.Logger;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;

/**
 * GemFireXD built-in system procedures that will get executed on every
 * distributed member.
 *
 * @author soubhikc
 */
@SuppressWarnings({ "unused", "WeakerAccess" })
public class GfxdSystemProcedures extends SystemProcedures {

  public static final Logger logger = Logger.getLogger(GfxdSystemProcedures.class);

  /**
   * Create distributed system users to authenticate connections and also
   * authenticate join request to peer members. These users will not become a
   * distributed system owner and hence limited by authorizations granted to
   * them by the system administrative users.
   *
   * <p>
   * A system administrative user connection can only create/drop distributed
   * system users or grant/revoke privileges from these users.
   *
   * @param userID
   *          User login id (also used as authorization id). <BR>
   * <BR>
   *          Can be prefixed with {@link Property#USER_PROPERTY_PREFIX
   *          gemfirexd.user.} for authentication scheme as
   *          {@link com.pivotal.gemfirexd.Constants#AUTHENTICATION_PROVIDER_BUILTIN
   *          BUILTIN} for compatibility for previous versions. For any other
   *          authentication scheme it can be anything recognized by the plugin
   *          implementation of
   *          {@link com.pivotal.gemfirexd.auth.callback.UserAuthenticator
   *          UserAuthenticator}. <BR>
   * <BR>
   * @param password
   *          User identification password that is encrypted and stored if using
   *          {@link com.pivotal.gemfirexd.Constants#AUTHENTICATION_PROVIDER_BUILTIN
   *          BUILTIN} auth-provider else not stored in GemFireXD. <BR>
   * <BR>
   *          Authentication plugin to utilize external means for e.g. LDAP
   *          server, PKCS public/private key encrption to validate the
   *          password. <BR>
   * <BR>
   *          GemFireXD pre-defined
   *          {@link com.pivotal.gemfirexd.Constants#AUTHENTICATION_PROVIDER_LDAP
   *          LDAP} scheme uses ldap server to verify credentials passed in
   *          during connection.
   *
   * @see #DROP_USER(String)
   */
  public static void CREATE_USER(String userID, String password)
      throws SQLException {

    if (GemFireXDUtils.TraceAuthentication) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
          "executing SYS.CREATE_USER for " + userID);
    }

    // A null userid is illegal but should not disconnect from current connection
    if (userID == null)
    {
        throw PublicAPI.wrapStandardException(StandardException.newException(
                SQLState.AUTH_INVALID_USER_NAME, "null"));
    }

    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    boolean cleanupOnError = false;
    try {
      ddLocked = lcc.getDataDictionary().lockForWriting(tc, false);

      final boolean isBUILTIN = AuthenticationServiceBase
          .isAuthenticationBUILTIN(null);
      userID = IdUtil.getDBUserId(userID, isBUILTIN);
      AuthenticationServiceBase.validateUserPassword(userID, password, !isBUILTIN);

      final String oldValue = GET_DATABASE_PROPERTY(userID);

      if (oldValue != null && oldValue.length() > 0) {
        throw StandardException.newException(
            SQLState.AUTH_USER_ALREADY_DEFINED, userID);
      }

      PropertyInfo.setDatabaseProperty(userID, password, true);
      cleanupOnError = true;

      // here we shall get encrypted password or 'null' meaning password not
      // stored.
      String encryptedpwd = GET_DATABASE_PROPERTY(userID);

      /* We store passwords only for "gemfirexd.user." prefixed dist-sys users
       * otherwise 'null'. So pass the key as value to remote hosts because
       * there too password won't be stored. Later, when credentials are passed
       * password shall be authenticated by external source.
       *
       * leaving it to NULL here will remove the users in remote VMs.
       *
       * hyphened so that it can't be used as key & this value will go to void as
       * it has here.
       */
      if (encryptedpwd == null) {
        encryptedpwd = "--W-O-NTS-TOR-E-" + userID;
      }

      // for ldap, value can be un-encrypted non-null and equal to value
      // because LDAP scheme uses the same gemfirexd.user. prefixed short
      // user name to capture full user DN.
      // i.e. gemfirexd.user.xxx=<full user dn>
      if (isBUILTIN) {
        if (encryptedpwd.equals(password)
            && !AuthenticationServiceBase.isEncrypted(password)) {
          SanityManager
              .THROWASSERT("shouldn't have stored the password in clear text '"
                  + encryptedpwd + "' for user '" + userID + "'");
        }
      }

      // in any case this shouldn't and can't be the same.
      if (GemFireXDUtils.TraceAuthentication) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
            "publishing SYS.CREATE_USER(" + userID + "," + encryptedpwd + ")");
      }

      publishMessage(new Object[] { userID, encryptedpwd }, false,
          GfxdSystemProcedureMessage.SysProcMethod.createUser, true, true);
      cleanupOnError = false;

      if (GemFireXDUtils.TraceAuthentication) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
            "returning from SYS.CREATE_USER(" + userID + "," + encryptedpwd
                + ")");
        SanityManager.ASSERT(
            (PropertyUtil.whereSet(userID, null) != PropertyUtil.SET_IN_JVM),
            "Cannot be a system user " + userID);
      }
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    } finally {
      if (cleanupOnError) {
        // create_user failed for some reason, so nullify the definition in local VM
        PropertyInfo.setDatabaseProperty(userID, null, false);
      }
      if (ddLocked) {
        try {
          lcc.getDataDictionary().unlockAfterWriting(tc, false);
        } catch (StandardException se) {
          SanityManager.DEBUG_PRINT("warning:EXCEPTION",
              "Failed to unlock DataDictionary for writing", se);
        }
      }
    }
  }

  /**
   * Change a distributed system users password credential. This definition is
   * effective for BUILTIN scheme only.
   *
   * <p>
   * A system administrative user connection can change any users password
   * otherwise user can only change his/her own password.
   *
   * @param userID
   *          User login id (also used as authorization id). <BR>
   * <BR>
   *          Can be prefixed with {@link Property#USER_PROPERTY_PREFIX
   *          gemfirexd.user.} for authentication scheme as
   *          {@link com.pivotal.gemfirexd.Constants#AUTHENTICATION_PROVIDER_BUILTIN
   *          BUILTIN} for compatibility for previous versions. For any other
   *          authentication scheme it can be anything recognized by the plugin
   *          implementation of
   *          {@link com.pivotal.gemfirexd.auth.callback.UserAuthenticator
   *          UserAuthenticator}. <BR>
   * <BR>
   * @param oldPassword
   *          User identification password that is encrypted and stored if using
   *          {@link com.pivotal.gemfirexd.Constants#AUTHENTICATION_PROVIDER_BUILTIN
   *          BUILTIN} auth-provider else not stored in GemFireXD. <BR>
   * <BR>
   *          Authentication plugin to utilize external means for e.g. LDAP
   *          server, PKCS public/private key encrption to change the password
   *          externally. <BR>
   * <BR>
   *          GemFireXD pre-defined
   *          {@link com.pivotal.gemfirexd.Constants#AUTHENTICATION_PROVIDER_LDAP
   *          LDAP} scheme doesn't change the password of the external server.
   * @param newPassword
   *          User identification new password that will be used for subsequent
   *          new connection validation.
   *
   * @see #CREATE_USER(String, String)
   */
  public static void CHANGE_PASSWORD(String userID, String oldPassword,
      String newPassword) throws SQLException {

    if (GemFireXDUtils.TraceAuthentication) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
          "executing SYS.CHANGE_PASSWORD for " + userID);
    }

    // A null userid is illegal but should not disconnect from current connection
    if (userID == null)
    {
        throw PublicAPI.wrapStandardException(StandardException.newException(
                SQLState.AUTH_INVALID_USER_NAME, "null"));
    }

    // don't allow for non-BUILTIN schemes
    AuthenticationService[] otherService = new AuthenticationService[1];
    final boolean isBUILTIN = AuthenticationServiceBase
        .isAuthenticationBUILTIN(otherService);
    if (!isBUILTIN) {
      throw PublicAPI.wrapStandardException(StandardException.newException(
          SQLState.AUTH_CANNOT_CHANGE_OLD_PASSWORD_FOR_NON_BUILTIN,
          otherService[0]));
    }

    final String origUserName = userID;
    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    DataDictionary dd = lcc.getDataDictionary();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    boolean cleanupOnError = false;
    boolean validateOldPassword = true;
    String oldValue = null;
    try {
      ddLocked = dd.lockForWriting(tc, false);

      // always allow self to change own password, else check if the user has
      // permissions to change someone else's password
      final String authorizationId = lcc.getAuthorizationId();
      final String userName = getBUILTINUserName(userID);
      if (lcc.usesSqlAuthorization()
          && !authorizationId.equals(IdUtil.getUserAuthorizationId(userName))) {
        List<?> list = dd.getRoutineList(dd.getSystemSchemaDescriptor()
            .getUUID().toString(), "CHANGE_PASSWORD",
            AliasInfo.ALIAS_NAME_SPACE_PROCEDURE_AS_CHAR);
        if (list.size() > 1) {
          throw StandardException.newException(
              SQLState.LANG_AMBIGUOUS_PROCEDURE_NAME, "SYS.CHANGE_PASSWORD");
        }
        if (list.size() != 1) {
          throw StandardException.newException(
              SQLState.LANG_NO_SUCH_METHOD_ALIAS, "SYS.CHANGE_PASSWORD", "");
        }
        StatementRoutinePermission.check(
            ((AliasDescriptor)list.get(0)).getUUID(), authorizationId, false,
            dd, tc);
        // admin users are allowed to skip old passwords
        validateOldPassword = (oldPassword != null && oldPassword.length() > 0);
      }
      else if (!lcc.usesSqlAuthorization()) {
        // allow admin user to skip old passwords regardless
        if (authorizationId.equals(dd.getAuthorizationDatabaseOwner())) {
          validateOldPassword = (oldPassword != null && oldPassword.length() > 0);
        }
      }

      userID = IdUtil.getDBUserId(userID, true);
      AuthenticationServiceBase
          .validateUserPassword(userID, newPassword, false);

      oldValue = GET_DATABASE_PROPERTY(userID);

      if (oldValue == null || oldValue.length() <= 0) {
        throw StandardException.newException(
            SQLState.AUTH_INVALID_USER_NAME, userID);
      }

      if (validateOldPassword) {
        Properties info = new Properties();
        info.setProperty(Attribute.USERNAME_ATTR, userName);
        info.setProperty(Attribute.PASSWORD_ATTR, oldPassword);
        info.setProperty(SecurityUtils.GFXD_SEC_PREFIX + "opType",
            "old_password_validation");
        String failure;
        if ((failure = InternalDriver.activeDriver().getAuthenticationService()
            .authenticate(lcc.getDbname(), info)) != null) {
          throw StandardException.newException(
              SQLState.AUTH_INVALID_OLD_PASSWORD, origUserName, failure);
        }
      }

      PropertyInfo.setDatabaseProperty(userID, newPassword, true);
      cleanupOnError = true;

      // here we shall get encrypted password or 'null' meaning password not
      // stored.
      String encryptedpwd = GET_DATABASE_PROPERTY(userID);

      /* We store passwords only for "gemfirexd.user." prefixed dist-sys users
       * otherwise 'null'. So pass the key as value to remote hosts because
       * there too password won't be stored. Later, when credentials are passed
       * password shall be authenticated by external source.
       *
       * leaving it to NULL here will remove the users in remote VMs.
       *
       * hyphened so that it can't be used as key & this value will go to void as
       * it has here.
       */
      if (encryptedpwd == null) {
        encryptedpwd = "--W-O-NTS-TOR-E-" + userID;
      }

      // below is always for BUILTIN schema
      if (encryptedpwd.equals(newPassword)
          && !AuthenticationServiceBase.isEncrypted(newPassword)) {
        SanityManager
            .THROWASSERT("shouldn't have stored the password in clear text '"
                + encryptedpwd + "' for user '" + userID + "'");
      }

      // in any case this shouldn't and can't be the same.
      if (GemFireXDUtils.TraceAuthentication) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
            "publishing SYS.CHANGE_PASSWORD(" + userID + "," + encryptedpwd + ")");
      }

      publishMessage(new Object[] { userID, oldPassword, encryptedpwd }, false,
          GfxdSystemProcedureMessage.SysProcMethod.changePassword, true, true);
      cleanupOnError = false;

      if (GemFireXDUtils.TraceAuthentication) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
            "returning from SYS.CHANGE_PASSWORD(" + userID + "," + encryptedpwd
                + ")");
        SanityManager.ASSERT(
            (PropertyUtil.whereSet(userID, null) != PropertyUtil.SET_IN_JVM),
            "Cannot be a system user " + userID);
      }
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    } finally {
      if (cleanupOnError) {
        // create_user failed for some reason, so nullify the definition in
        // local VM
        PropertyInfo.setDatabaseProperty(userID, oldValue, false);
      }
      if (ddLocked) {
        try {
          dd.unlockAfterWriting(tc, false);
        } catch (StandardException se) {
          SanityManager.DEBUG_PRINT("warning:EXCEPTION",
              "Failed to unlock DataDictionary for writing", se);
        }
      }
    }
  }

  /**
   * Drop a distributed system users created via
   * {@link #CREATE_USER(String,String)}.
   *
   * @param userID
   *          Existing user ID that must be dropped.
   *
   * @see #CREATE_USER(String, String)
   */
  public static void DROP_USER(String userID) throws SQLException {

    if (GemFireXDUtils.TraceAuthentication) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
          "executing SYS.DROP_USER(" + userID + ")");
    }

    // A null userid is illegal but should not disconnect from current
    // connection
    if (userID == null) {
      throw PublicAPI.wrapStandardException(StandardException
          .newException(SQLState.AUTH_INVALID_USER_NAME, "null"));
    }

    final String origUser = userID;
    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    String oldValue = null;
    boolean resetOldValue = false;
    try {
      ddLocked = lcc.getDataDictionary().lockForWriting(tc, false);

      boolean isAuthBuiltIn = AuthenticationServiceBase
          .isAuthenticationBUILTIN(null);
      userID = IdUtil.getDBUserId(userID, isAuthBuiltIn);

      oldValue = GET_DATABASE_PROPERTY(userID);
      if (oldValue == null && isAuthBuiltIn) {
        // no such user
        throw PublicAPI.wrapStandardException(StandardException
            .newException(SQLState.AUTH_INVALID_USER_NAME, origUser));
      }
      resetOldValue = true;

      final Object[] params = new Object[] { userID };

      GfxdSystemProcedureMessage.SysProcMethod.dropUser.processMessage(params,
          Misc.getMyId());
      publishMessage(params, false,
          GfxdSystemProcedureMessage.SysProcMethod.dropUser, true, true);

      resetOldValue = false;
      if (GemFireXDUtils.TraceAuthentication) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
            "returning from SYS.DROP_USER(" + userID + ")");
      }
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    } finally {
      if (resetOldValue && oldValue != null) {
        PropertyInfo.setDatabaseProperty(userID, oldValue, false);
      }
      if (ddLocked) {
        try {
          lcc.getDataDictionary().unlockAfterWriting(tc, false);
        } catch (StandardException se) {
          SanityManager.DEBUG_PRINT("warning:EXCEPTION",
              "Failed to unlock DataDictionary for writing", se);
        }
      }
    }
  }

  private static String getBUILTINUserName(String userID) {
    if (userID.startsWith(Property.USER_PROPERTY_PREFIX)) {
      userID = userID.substring(Property.USER_PROPERTY_PREFIX.length());
    }
    //SQLF:BC
    else if (userID.startsWith(Property.SQLF_USER_PROPERTY_PREFIX)) {
      userID = userID.substring(Property.SQLF_USER_PROPERTY_PREFIX.length());
    }
    return userID;
  }

  /**
   * Show all distributed system users using BUILTIN authentication scheme
   * created via {@link #CREATE_USER(String,String)}, or defined via system
   * property.
   *
   * @param users
   *          Returned set of users.
   *
   * @see #CREATE_USER(String, String)
   */
  public static void SHOW_USERS(ResultSet[] users) throws SQLException {

    if (GemFireXDUtils.TraceAuthentication) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
          "executing SYS.SHOW_USERS()");
    }

    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();

    try {
      final Properties allProps = lcc.getTransactionExecute().getProperties();
      final String dbOwner = lcc.getDataDictionary()
          .getAuthorizationDatabaseOwner();
      final CustomRowsResultSet.FetchDVDRows fetchRows =
          new CustomRowsResultSet.FetchDVDRows() {

        final Iterator<String> keys = allProps.stringPropertyNames().iterator();
        @Override
        public boolean getNext(DataValueDescriptor[] template)
            throws StandardException {
          String key, user;
          while (this.keys.hasNext()) {
            key = this.keys.next();
            if (key != null && key.startsWith(Property.USER_PROPERTY_PREFIX)) {
              user = key.substring(Property.USER_PROPERTY_PREFIX.length());
              template[0].setValue(user);
              template[1].setValue(user.equals(dbOwner) ? "DBA" : "USER");
              return true;
            }
            //SQLF:BC
            else if (key != null && key.startsWith(Property.SQLF_USER_PROPERTY_PREFIX)) {
              user = key.substring(Property.SQLF_USER_PROPERTY_PREFIX.length());
              template[0].setValue(user);
              template[1].setValue(user.equals(dbOwner) ? "DBA" : "USER");
              return true;
            }
          }
          return false;
        }
      };
      users[0] = new CustomRowsResultSet(fetchRows, usersColumnInfo);
    } catch (StandardException se) {
        throw PublicAPI.wrapStandardException(se);
    }
  }

  private static final ResultColumnDescriptor[] usersColumnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor("NAME", Types.VARCHAR,
          false, 256),
      EmbedResultSetMetaData.getResultColumnDescriptor("TYPE", Types.VARCHAR,
          false, 8)
  };


  /**
   * Encrypts a password and returns the encrypted text in result set
   *
   * @param userID userId whose password is to be encrypted
   * @param password plain text password
   * @param transformation algorithm to be used, default is AES, if null is passed for arg
   * @param keySize encryption key size, default is 128 if a value <=0 is passed for this arg
   * @param encryptedPwdRs result encrypted password
   */
  public static void ENCRYPT_PASSWORD(String userID, String password,
      String transformation, int keySize, ResultSet[] encryptedPwdRs)
      throws SQLException {

    if (GemFireXDUtils.TraceAuthentication || GemFireXDUtils.TraceSysProcedures) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
          "executing SYS.ENCRYPT_PASSWORD(), " +
              "userID=" + userID + ", transformation=" + transformation + ", keySize=" + keySize);
    }

    try {
      String user = IdUtil.getDBUserId(userID, false);

      String algo = transformation == null
          ? GfxdConstants.PASSWORD_PRIVATE_KEY_ALGO_DEFAULT : GemFireXDUtils
          .getPrivateKeyAlgorithm(transformation);
      if (keySize <= 0) {
        keySize = GfxdConstants.PASSWORD_PRIVATE_KEY_SIZE_DEFAULT;
      }
      GemFireXDUtils.initializePrivateKey(algo, keySize, null);

      final String encryptedString = AuthenticationServiceBase.ID_PATTERN_LDAP_SCHEME_V1 +
          GemFireXDUtils.encrypt(password, transformation,
              GemFireXDUtils.getUserPasswordCipherKeyBytes(user,
                  transformation, keySize));

      final CustomRowsResultSet.FetchDVDRows fetchRows =
          new CustomRowsResultSet.FetchDVDRows() {

            boolean resultReturned = false;

            @Override
            public boolean getNext(DataValueDescriptor[] template)
                throws StandardException {
              if (!resultReturned) {
                template[0].setValue(userID + " = " + encryptedString);
                resultReturned = true;
                return true;
              }
              return false;
            }

          };
      encryptedPwdRs[0] = new CustomRowsResultSet(fetchRows, encryptColumnInfo);
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  private static final ResultColumnDescriptor[] encryptColumnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor("ENCRYPTED_PASSWORD", Types.VARCHAR,
          false, Limits.DB2_VARCHAR_MAXWIDTH),
  };


  /**
   * Set the percentage of heap at or above which the GFXD server instance is
   * considered in danger of becoming inoperable due to garbage collection
   * pauses or out of memory exceptions.
   *
   * <p>
   * Changing this value can cause LowMemoryException or SQLException with
   * SQLState XCL52.S (query cancelled or timedout) to be thrown during any DML
   * operations i.e. inserts/updates/deletes/selects etc..
   *
   * <p>
   * Only current VM is set with this attribute and other overload propagates
   * the setting to remote VMs.
   *
   * <p>
   * When using this threshold, the VM must be launched with the
   * <code>-Xmx</code> and <code>-Xms</code> switches set to the same values.
   * Many virtual machine implementations have additional VM switches to control
   * the behavior of the garbage collector. We suggest that you investigate
   * tuning the garbage collector when using this type of eviction controller. A
   * collector that frequently collects is needed to keep our heap usage up to
   * date. In particular, on the Sun <A
   * href="http://java.sun.com/docs/hotspot/gc/index.html">HotSpot</a> VM, the
   * <code>-XX:+UseConcMarkSweepGC</code> flag needs to be set, and
   * <code>-XX:CMSInitiatingOccupancyFraction=N</code> should be set with N
   * being a percentage that is less than the {@link ResourceManager} critical
   * and eviction heap thresholds.
   *
   * <p>
   * Example: setting a critical heap percent to 50 means 50% of max tenured
   * generation bytes (say 2048 MB) equates to critical heap threshold limit as
   * 1024 MB.
   *
   * <p>
   * Similarly, setting eviction heap percent to 30 means 30% of max tenured
   * generation bytes (say 2048 MB) equates to eviction heap threshold limit as
   * 614.4 MB.
   *
   * <p>
   * An Eviction heap threshold must be less than Critical heap threshold. If a
   * VM reaches its eviction threshold limit, GemFireXD will attempt to re-claim
   * memory depending on the {@link EvictionAttributes} of the tables.
   *
   * <p>
   * Default CRITICAL_HEAP_PERCENT or EVICTION_HEAP_PERCENT is 0 which means its
   * disabled by default.
   *
   * <p>
   * Property 'gemfire.disableLowMemoryException' can be used to suppress any
   * LowMemoryException whereas query cancellation cannot be suppressed.
   *
   * <p>
   * A threshold thickness percentage can be defined using
   * 'gemfire.thresholdThickness' property that controls the heap usage must go
   * down by (THRESHOLD minus THRESHOLD-THICKNESS) before critical down event
   * i.e. VM is considered in non-critical memory state.
   *
   * <p>
   * The JRockit VM has similar flags, <code>-Xgc:gencon</code> and
   * <code>-XXgcTrigger:N</code>, which are required if using this feature.
   * Please Note: the JRockit gcTrigger flag is based on heap free, not heap in
   * use like the GemFire parameter. This means you need to set gcTrigger to
   * 100-N. for example, if your eviction threshold is 30 percent, you will need
   * to set gcTrigger to 70 percent.
   *
   * On the IBM VM, the flag to get a similar collector is
   * <code>-Xgcpolicy:gencon</code>, but there is no corollary to the
   * gcTrigger/CMSInitiatingOccupancyFraction flags, so when using this feature
   * with an IBM VM, the heap usage statistics might lag the true memory usage
   * of the VM, and thresholds may need to be set sufficiently high that the VM
   * will initiate GC before the thresholds are crossed.
   *
   * @param heapPercentage
   *          a percentage of the maximum tenured heap for the VM.
   *
   * @throws IllegalStateException
   *           if the heapPercentage value is not >= 0 or <= 100 or when less
   *           than the current eviction heap percentage.
   *
   * @see #GET_CRITICAL_HEAP_PERCENTAGE()
   * @see #GET_EVICTION_HEAP_PERCENTAGE()
   */
  public static void SET_CRITICAL_HEAP_PERCENTAGE(float heapPercentage) {

    final InternalResourceManager rmgr = Misc.getGemFireCache()
        .getResourceManager();
    rmgr.setCriticalHeapPercentage(heapPercentage);
    SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_SYS_PROCEDURES,
        "configured critical heap percentage to "
            + rmgr.getCriticalHeapPercentage());
  }

  public static void SET_CRITICAL_OFFHEAP_PERCENTAGE(float offHeapPercentage) {

    final InternalResourceManager rmgr = Misc.getGemFireCache()
        .getResourceManager();
    rmgr.setCriticalOffHeapPercentage(offHeapPercentage);
    SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_SYS_PROCEDURES,
        "configured critical off heap percentage to "
            + rmgr.getCriticalOffHeapPercentage());
  }

  /**
   * An overload that will set critical heap percent to VMs part of the server
   * group. A NULL value in server group will mean setting percentage to ALL
   * GFXD members.
   *
   * @param heapPercentage
   *          a percentage of the maximum tenured heap for the VM
   * @param serverGroups
   *          a comma delimited list of server group names on which this
   *          procedure is to be executed. Optionally it can be NULL indicating
   *          all.
   *
   * @throws IllegalStateException
   *           if the heapPercentage value is not >= 0 or <= 100 or when less
   *           than the current eviction heap percentage
   * @see #SET_CRITICAL_HEAP_PERCENTAGE(float)
   */
  public static void SET_CRITICAL_HEAP_PERCENTAGE_SG(float heapPercentage,
      String serverGroups) throws SQLException, StandardException {

    // first set the critical-heap-percentage locally if requested
    final Object[] args = new Object[] { heapPercentage,
        serverGroups };
    GfxdSystemProcedureMessage.SysProcMethod.setCriticalHeapPercentage
        .processMessage(args, Misc.getMyId());

    // send to other nodes
    publishMessage(args, true,
        GfxdSystemProcedureMessage.SysProcMethod.setCriticalHeapPercentage,
        true, true);
  }

  public static void SET_CRITICAL_OFFHEAP_PERCENTAGE_SG(float offHeapPercentage,
      String serverGroups) throws SQLException, StandardException {

    // first set the critical-off-heap-percentage locally if requested
    final Object[] args = new Object[] { offHeapPercentage,
        serverGroups };
    GfxdSystemProcedureMessage.SysProcMethod.setCriticalOffHeapPercentage
        .processMessage(args, Misc.getMyId());

    // send to other nodes
    publishMessage(args, true,
        GfxdSystemProcedureMessage.SysProcMethod.setCriticalOffHeapPercentage,
        true, true);
  }

  /**
   * Get the percentage of heap at or above which the cache is considered in
   * danger of becoming inoperable.
   *
   * @return either the current or recently used percentage of the maximum
   *         tenured heap
   * @see #SET_CRITICAL_HEAP_PERCENTAGE(float)
   */
  public static float GET_CRITICAL_HEAP_PERCENTAGE() {
    return Misc.getGemFireCache().getResourceManager()
        .getCriticalHeapPercentage();
  }

  public static float GET_CRITICAL_OFFHEAP_PERCENTAGE() {
    return Misc.getGemFireCache().getResourceManager()
        .getCriticalOffHeapPercentage();
  }

  /**
   * Set the percentage of heap at or above which the eviction should begin on
   * table's underlying region configured for
   * {@linkplain EvictionAttributes#createLRUHeapAttributes() HeapLRU eviction}.
   *
   * <p>
   * Changing this value may cause eviction to begin immediately.
   *
   * <p>
   * Only one change to this attribute or critical heap percentage will be
   * allowed at any given time and its effect will be fully realized before the
   * next change is allowed.
   *
   * This feature requires additional VM flags to perform properly. See
   * {@link #SET_CRITICAL_HEAP_PERCENTAGE(float)} for details.
   *
   * @param heapPercentage
   *          a percentage of the maximum tenured heap for the VM
   *
   * @throws IllegalStateException
   *           if the heapPercentage value is not >= 0 or <= 100 or when greater
   *           than the current critical heap percentage.
   *
   * @see #GET_EVICTION_HEAP_PERCENTAGE()
   * @see #GET_CRITICAL_HEAP_PERCENTAGE()
   */
  public static void SET_EVICTION_HEAP_PERCENTAGE(float heapPercentage) {

    final InternalResourceManager rmgr = Misc.getGemFireCache()
        .getResourceManager();
    rmgr.setEvictionHeapPercentage(heapPercentage);
    SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_SYS_PROCEDURES,
        "configured eviction heap percentage to "
            + rmgr.getEvictionHeapPercentage());
  }

  public static void SET_EVICTION_OFFHEAP_PERCENTAGE(float offHeapPercentage) {

    final InternalResourceManager rmgr = Misc.getGemFireCache()
        .getResourceManager();
    rmgr.setEvictionOffHeapPercentage(offHeapPercentage);
    SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_SYS_PROCEDURES,
        "configured eviction off heap percentage to "
            + rmgr.getEvictionOffHeapPercentage());
  }

  /**
   * An overload that will set eviction heap percent to VMs part of the server
   * group. A NULL value in server group will mean setting percentage to ALL
   * GFXD members.
   *
   * @param heapPercentage
   *          a percentage of the maximum tenured heap for the VM
   * @param serverGroups
   *          a comma delimited list of server group names on which this
   *          procedure is to be executed. Optionally it can be NULL indicating
   *          all.
   *
   * @throws IllegalStateException
   *           if the heapPercentage value is not >= 0 or <= 100 or when less
   *           than the current eviction heap percentage
   *
   * @see #SET_EVICTION_HEAP_PERCENTAGE(float)
   */
  public static void SET_EVICTION_HEAP_PERCENTAGE_SG(float heapPercentage,
      String serverGroups) throws SQLException, StandardException {

    // first set the eviction-heap-percentage locally if requested
    final Object[] args = new Object[] { heapPercentage,
        serverGroups };
    GfxdSystemProcedureMessage.SysProcMethod.setEvictionHeapPercentage
        .processMessage(args, Misc.getMyId());

    // send to other nodes
    publishMessage(args, true,
        GfxdSystemProcedureMessage.SysProcMethod.setEvictionHeapPercentage,
        true, true);
  }

  public static void SET_EVICTION_OFFHEAP_PERCENTAGE_SG(float offHeapPercentage,
      String serverGroups) throws SQLException, StandardException {

    // first set the eviction-offheap-percentage locally if requested
    final Object[] args = new Object[] { offHeapPercentage,
        serverGroups };
    GfxdSystemProcedureMessage.SysProcMethod.setEvictionOffHeapPercentage
        .processMessage(args, Misc.getMyId());

    // send to other nodes
    publishMessage(args, true,
        GfxdSystemProcedureMessage.SysProcMethod.setEvictionOffHeapPercentage,
        true, true);
  }

  /**
   * Get the percentage of heap at or above which the eviction should begin on
   * Regions configured for
   * {@linkplain EvictionAttributes#createLRUHeapAttributes() HeapLRU eviction}.
   *
   * @return either the current or recently used percentage of the maximum
   *         tenured heap
   * @see #SET_EVICTION_HEAP_PERCENTAGE(float)
   */
  public static float GET_EVICTION_HEAP_PERCENTAGE() {
    return Misc.getGemFireCache().getResourceManager()
        .getEvictionHeapPercentage();
  }

  public static float GET_EVICTION_OFFHEAP_PERCENTAGE() {
    return Misc.getGemFireCache().getResourceManager()
        .getEvictionOffHeapPercentage();
  }

  /**
   * Get two results: a string containing all the network servers available in
   * the distributed system; other the preferred server w.r.t. load-balancing to
   * connect to from a JDBC client as out parameter. A set of servers to be
   * excluded from consideration can be passed as a comma-separated string (e.g.
   * to ignore the failed server during failover).
   *
   * The format of network server list is:
   *
   * host1[port1]{kind1},host2[port2]{kind2},...
   *
   * i.e. comma-separated list of each network server followed by the
   * <code>VMKind</code> of the VM in curly braces. The network servers on
   * stand-alone locators are given preference and appear at the front. If the
   * output column exceeds the max size of LONGVARCHAR column
   * {@link TypeId#LONGVARCHAR_MAXWIDTH} then null is returned for this result
   * in which case the client is supposed to get the list from the SYS.MEMBERS
   * VTI table in a separate call.
   *
   * This is primarily to avoid making two calls to the servers from the clients
   * during connection creation or failover.
   */
  public static void GET_ALLSERVERS_AND_PREFSERVER(String excludedServers,
      String[] prefServerName, int[] prefServerPort, String[] allNetServers)
      throws SQLException {
    // get all known network servers
    String servers = GemFireXDUtils.getGfxdAdvisor().getAllDRDAServers();
    if (servers.length() < (TypeId.LONGVARCHAR_MAXWIDTH - 5)) {
      allNetServers[0] = servers;
    }
    else {
      allNetServers[0] = null;
    }
    // pick best server without caring for server groups right now
    GET_PREFSERVER(excludedServers, prefServerName, prefServerPort);
  }

  /**
   * Get two results: a CLOB containing all the network servers available in
   * the distributed system; other the preferred server w.r.t. load-balancing to
   * connect to from a JDBC client as out parameter. A set of servers to be
   * excluded from consideration can be passed as a comma-separated string (e.g.
   * to ignore the failed server during failover).
   *
   * The format of network server list is:
   *
   * host1[port1]{kind1},host2[port2]{kind2},...
   *
   * i.e. comma-separated list of each network server followed by the
   * <code>VMKind</code> of the VM in curly braces. The network servers on
   * stand-alone locators are given preference and appear at the front. If the
   * output column exceeds the max size of LONGVARCHAR column
   * {@link TypeId#LONGVARCHAR_MAXWIDTH} then null is returned for this result
   * in which case the client is supposed to get the list from the SYS.MEMBERS
   * VTI table in a separate call.
   *
   * This is primarily to avoid making two calls to the servers from the clients
   * during connection creation or failover.
   * <p>
   * This differs from GET_ALLSERVERS_AND_PREFSERVER in returning
   * "allNetServers" as a CLOB rather than a LONGVARCHAR for the rare case
   * when it exceeds 32K.
   */
  public static void GET_ALLSERVERS_AND_PREFSERVER2(String excludedServers,
      String[] prefServerName, int[] prefServerPort, Clob[] allNetServers)
      throws SQLException {
    final String[] allServers = new String[1];
    GET_ALLSERVERS_AND_PREFSERVER(excludedServers, prefServerName, prefServerPort, allServers);
    if (allServers[0] != null) {
      allNetServers[0] = new HarmonySerialClob(allServers[0]);
    } else {
      allNetServers[0] = null;
    }
  }

  /**
   * Get the preferred server to which the next connection should be made. A set
   * of servers to be excluded from consideration can be passed as a
   * comma-separated string (e.g. to ignore the failed server during failover).
   * No server groups are provided here since JDBC clients do not know about
   * those currently.
   */
  public static void GET_PREFSERVER(String excludedServers,
      String[] prefServerName, int[] prefServerPort) throws SQLException {
    prefServerName[0] = null;
    prefServerPort[0] = -1;
    // add the special group for DRDA servers
    ServerLocation server = GemFireXDUtils.getPreferredServer(Collections
        .singletonList(ServerType.DRDA.getServerGroupName()), null,
        excludedServers, true);
    if (server != null) {
      prefServerName[0] = server.getHostName();
      prefServerPort[0] = server.getPort();
    }
  }

  /**
   * Enable execution statistics capturing for individual statements.
   */
  public static void SET_STATEMENT_STATISTICS(int enable)
      throws SQLException, StandardException {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "GfxdSystemProcedure: Switching " + (enable == 0 ? "Off" : "On")
                + " statement statistics collection. ");
      }
    }
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    lcc.setStatsEnabled(enable == 1, lcc.getStatisticsTiming(),
        lcc.explainConnection());
    // below code is not honoured and the setDatabaseProperty actually does
    // nothing; now this flag is sent in each message separately from query node
    final String key = Property.STATEMENT_STATISTICS_MODE;
    final String value = enable == 0 ? null : "TRUE";
    publishMessage(new Object[] { key, value }, false,
        GfxdSystemProcedureMessage.SysProcMethod.setDatabaseProperty, false, false);
  }

  /**
   * Enable execution statistics capturing globally for all statements.
   */
  public static void SET_GLOBAL_STATEMENT_STATISTICS(Boolean enableStats,
      Boolean enableTimeStats) throws SQLException, StandardException {

    final Object[] params = new Object[] { enableStats, enableTimeStats };
    // first process locally
    GfxdSystemProcedureMessage.SysProcMethod.setStatementStats.processMessage(
        params, Misc.getMyId());
    // then publish to other members including locators
    publishMessage(params, false,
        GfxdSystemProcedureMessage.SysProcMethod.setStatementStats, false,
        false);
  }

  /**
   * Enable to collect table level transformed statistics of statement
   * executions.
   *
   * @param archiveFile
   *          stats archive file name (absolute or relative path). null disables
   *          stats collection.
   */
  public static void SET_STATISTICS_SUMMARY(String archiveFile)
      throws SQLException {
    final String key = Property.STATISTICS_SUMMARY_MODE;
    final String value = archiveFile != null && archiveFile.length() > 0
        ? archiveFile : null;

    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    try {
      ddLocked = lcc.getDataDictionary().lockForWriting(tc, false);

      PropertyInfo.setDatabaseProperty(key, value, (value != null));

      publishMessage(new Object[] { key, value }, false,
          GfxdSystemProcedureMessage.SysProcMethod.setDatabaseProperty, true,
          false);
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    } finally {
      if (ddLocked) {
        try {
          lcc.getDataDictionary().unlockAfterWriting(tc, false);
        } catch (StandardException se) {
          SanityManager.DEBUG_PRINT("warning:EXCEPTION",
              "Failed to unlock DataDictionary for writing", se);
        }
      }
    }
  }

  /**
   * This procedure sets up per connection query plan generation. Any schema
   * switch will be tracked and recorded by this connection. To retrieve the
   * query plans, one will have to switch back to individual schemas and execute
   * 'select stmt_id, stmt_text from sys.statementplans';
   */
  public static void SET_EXPLAIN_CONNECTION(int enable)
      throws SQLException, StandardException {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "GfxdSystemProcedure: Switching " + (enable == 0 ? "Off" : "On")
                + " connection level plan collection ");
      }
    }

    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();

    if (enable == 0) {
      lcc.setStatsEnabled(lcc.statsEnabled(), lcc.getStatisticsTiming(),
          false /*disable*/);
    }
    else {
      lcc.setStatsEnabled(lcc.statsEnabled(), lcc.getStatisticsTiming(),
          true /*enable*/);
    }

    final String key = Property.STATEMENT_EXPLAIN_MODE;
    final String value = enable == 0 ? null : "TRUE";
    PropertyInfo.setDatabaseProperty(key, value, (value != null));
    // lets publish this message as database property so that all VMs start
    // recording GemFireXD level stats via setObserver
    // @see FabricDatabase#apply(..)
    // ideally, we should use something like DistributionManager's constructor
    // setting DistributionStats.enableClockStats
    // It should invoke a interface method where GemFireXD can handle for query
    // plans.
    publishMessage(new Object[] { key, value }, false,
        GfxdSystemProcedureMessage.SysProcMethod.setDatabaseProperty, false,
        false);
  }

  /**
   * This procedure returns the current status of the explain mode.
   *
   * If the EXPLAIN mode is non-zero, meaning that it is ON, then statements are
   * being EXPLAIN'd only via this connection.
   *
   * @return 0 if EXPLAIN mode is off, non-zero if on.
   */
  public static int GET_EXPLAIN_CONNECTION() throws SQLException {
    return ConnectionUtil.getCurrentLCC().explainConnection() ? 1 : 0;
  }

  /**
   * Return all the DDLs executed in the system so far as a ResultSet with three
   * columns:
   *
   * SCHEMANAME | OBJECTNAME | SQLTEXT
   *
   * The "exportAll" parameter allows exporting everything including
   * configuration commands using system procedures.
   */
  public static void EXPORT_ALL_DDLS(final Boolean exportAll,
      final ResultSet[] rs)
      throws SQLException, StandardException, CacheException,
      InterruptedException {
    // take the read lock on DataDictionary to flush any existing DDLs
    final GemFireStore memStore = Misc.getMemStore();
    final GfxdDataDictionary dd = memStore.getDatabase().getDataDictionary();

    if (dd == null) {
      throw Util.generateCsSQLException(SQLState.SHUTDOWN_DATABASE,
          Attribute.GFXD_DBNAME);
    }

    dd.lockForReadingRT(null);
    try {
      // create a wrapper GfxdDDLRegionQueue to get the DDLs in proper order
      final GfxdDDLRegionQueue ddlQ = new GfxdDDLRegionQueue(memStore
          .getDDLStmtQueue().getRegion());
      ddlQ.initializeQueue(dd);
      final CustomRowsResultSet.FetchDVDRows fetchRows =
          new CustomRowsResultSet.FetchDVDRows() {

        private final List<GfxdDDLQueueEntry> allDDLs =
            ddlQ.peekAndRemoveFromQueue(-1, -1);
        private final Iterator<GfxdDDLQueueEntry> ddlIter = ddlQ
            .getPreprocessedDDLQueue(allDDLs, null, null, null, false)
            .iterator();

        @Override
        public boolean getNext(DataValueDescriptor[] template)
            throws StandardException {
          final boolean debugOn = SanityManager.DEBUG_ON("ExportDDLs");
          String currentSchema;
          // get all elements in the queue removing them from the queue
          // but not from the underlying region
          while (this.ddlIter.hasNext()) {
            GfxdDDLQueueEntry entry = this.ddlIter.next();
            final Object val = entry.getValue();
            if (debugOn) {
              SanityManager.DEBUG_PRINT("ExportDDLs", "Read queue entry " + val
                  + " of type " + val.getClass());
            }
            if (val instanceof DDLConflatable) {
              final DDLConflatable ddl = (DDLConflatable)val;
              currentSchema = ddl.getCurrentSchema();
              if (currentSchema == null) {
                currentSchema = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
              }
              template[0].setValue(currentSchema);
              String objectName = ddl.getKeyToConflate();
              if (objectName == null) {
                objectName = ddl.getRegionToConflate();
              }
              template[1].setValue(objectName);
              template[2].setValue(ddl.getValueToConflate());
              return true;
            }
            else {
              if (exportAll != null && exportAll) {
                final AbstractGfxdReplayableMessage msg =
                    (AbstractGfxdReplayableMessage)val;
                final String sql = msg.getSQLStatement();
                if (sql != null) {
                  currentSchema = msg.getSchemaName();
                  if (currentSchema == null) {
                    template[0].setToNull();
                  }
                  else {
                    template[0].setValue(currentSchema);
                  }
                  template[1].setToNull();
                  template[2].setValue(sql);
                  return true;
                }
              }
            }
          }
          return false;
        }
      };
      rs[0] = new CustomRowsResultSet(fetchRows, exportDDLsColumnInfo);
    } finally {
      dd.unlockAfterReading(null);
    }
  }

  private static final ResultColumnDescriptor[] exportDDLsColumnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor("SCHEMANAME",
          Types.VARCHAR, true, 256),
      EmbedResultSetMetaData.getResultColumnDescriptor("OBJECTNAME",
          Types.VARCHAR, true, 256),
      EmbedResultSetMetaData.getResultColumnDescriptor("SQLTEXT", Types.CLOB,
          false)
  };

  /**
   * Install a jar file in the database using given jar bytes.
   *
   * SQLJ.INSTALL_JAR_BYTES(Blob,String)
   *
   * @param jarBlob
   *          Raw bytes of the jar file as a Blob to be installed in the
   *          database.
   * @param jarName
   *          SQL name of jar to be installed.
   *
   * @exception SQLException
   *              Error replacing jar file.
   */
  public static void INSTALL_JAR_BYTES(Blob jarBlob, String jarName)
      throws SQLException {

    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "INSTALL_JAR_BYTES called for alias " + jarName);
    }

    try {

      // NULL BLOB value or jar name is illegal
      if ((jarBlob == null) || (jarName == null)) {
        throw StandardException.newException(SQLState.ENTITY_NAME_MISSING);
      }

      LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
      String[] st = GfxdJarMessage.getSchemaName(jarName.trim(), lcc);

      JarUtil.install(lcc, st[0], st[1], jarBlob);
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * Replace a jar file in the database using given jar bytes.
   *
   * SQLJ.REPLACE_JAR_BYTES(Blob,String)
   *
   * @param jarBlob
   *          Raw bytes of the jar file as a Blob to be installed in the
   *          database.
   * @param jarName
   *          SQL name of jar to be replaced.
   *
   * @exception SQLException
   *              Error replacing jar file.
   */
  public static void REPLACE_JAR_BYTES(Blob jarBlob, String jarName)
      throws SQLException {

    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "REPLACE_JAR_BYTES called for alias " + jarName);
    }

    try {
      // NULL BLOB value or jar name is illegal
      if ((jarBlob == null) || (jarName == null)) {
        throw StandardException.newException(SQLState.ENTITY_NAME_MISSING);
      }

      LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
      String[] st = GfxdJarMessage.getSchemaName(jarName.trim(), lcc);

      JarUtil.replace(lcc, st[0], st[1], jarBlob);
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * Start the rebalancing of buckets of all partitioned tables and
   * wait for it to complete.
   */
  public static void REBALANCE_ALL_BUCKETS() throws SQLException {

    try {
      final RebalanceOperation rebalanceOp = Misc.getGemFireCache()
          .getResourceManager().createRebalanceFactory().start();
      rebalanceOp.getResults();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      Misc.checkIfCacheClosing(ie);
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  /**
   * Get catalog metadata from SnappyHiveExternalCatalog.
   *
   * @param operation   one of the read operation types with prefix CATALOG_ in snappydata.thrift
   * @param args        thrift serialized CatalogMetadataRequest
   * @param outMetadata output thrift serialized CatalogMetadataDetails
   */
  public static void GET_CATALOG_METADATA(int operation, Blob args,
      Blob[] outMetadata) throws SQLException {
    try {
      final CatalogMetadataRequest request = new CatalogMetadataRequest();
      Assert.assertTrue(GemFireXDUtils.readThriftObject(request,
          args.getBytes(1, (int)args.length())) == 0);
      args.free();
      if (GemFireXDUtils.TraceSysProcedures) {
        logger.info("Executing GET_CATALOG_METADATA operation=" + operation + ": " + request);
      }

      ExternalCatalog catalog = Misc.getMemStore().getExistingExternalCatalog();
      final CatalogMetadataDetails result = new CatalogMetadataDetails();
      final LocalRegion region = catalog.fillCatalogMetadata(operation, request, result);
      // need to fill in additional bucket/column information for getTable
      if (operation == snappydataConstants.CATALOG_GET_TABLE &&
          result.isSetCatalogTable() && region != null) {
        CatalogTableObject catalogTable = result.getCatalogTable();
        String schema = request.getSchemaName();
        String table = request.getNameOrPattern();
        // set other attributes: redundancy, bucket to server/replica to server mapping
        if (region.getAttributes().getPartitionAttributes() != null) {
          getBucketToServerMapping((PartitionedRegion)region, catalogTable);
        } else {
          getReplicaServerMapping((DistributedRegion)region, catalogTable);
        }
      }
      outMetadata[0] = new HarmonySerialBlob(GemFireXDUtils.writeThriftObject(result));
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  /**
   * Update catalog metadata of SnappyHiveExternalCatalog.
   *
   * @param operation one of the update operation types with prefix CATALOG_ in snappydata.thrift
   * @param args      thrift serialized CatalogMetadataDetails
   */
  public static void UPDATE_CATALOG_METADATA(int operation, Blob args) throws SQLException {
    try {
      final CatalogMetadataDetails request = new CatalogMetadataDetails();
      Assert.assertTrue(GemFireXDUtils.readThriftObject(request,
          args.getBytes(1, (int)args.length())) == 0);
      args.free();
      if (GemFireXDUtils.TraceSysProcedures) {
        logger.info("Executing UPDATE_CATALOG_METADATA operation=" + operation + ": " + request);
      }

      ExternalCatalog catalog = Misc.getMemStore().getExistingExternalCatalog();
      String currentUser = ((GenericLanguageConnectionContext)ConnectionUtil
          .getCurrentLCC()).getUserName();
      catalog.updateCatalogMetadata(operation, request, currentUser);
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  private static void getReplicaServerMapping(final DistributedRegion region,
      final CatalogTableObject catalogTable) {
    // replica to server mapping
    Set<InternalDistributedMember> owners = new UnifiedSet<>();
    Set<InternalDistributedMember> replicas =
        region.getDistributionAdvisor().adviseInitializedReplicates();
    Map<InternalDistributedMember, String> mbrToServerMap = GemFireXDUtils
        .getGfxdAdvisor().getAllNetServersWithMembers();

    ArrayList<String> replicaOwners = new ArrayList<>(owners.size());
    if (ServerGroupUtils.isDataStore()) {
      owners.add(Misc.getGemFireCache().getMyId());
    }
    owners.addAll(replicas);
    for (InternalDistributedMember node : owners) {
      String netServer = mbrToServerMap.get(node);
      if (netServer != null) {
        replicaOwners.add(netServer);
      }
    }
    BucketOwners bucketOwners = new BucketOwners(-1);
    bucketOwners.setSecondaries(replicaOwners);
    catalogTable.setBucketOwners(Collections.singletonList(bucketOwners));
  }

  /**
   * Returns the list of index columns.
   */
  public static List<String> getIndexColumns(LocalRegion region)
      throws StandardException {
    GemFireContainer container = (GemFireContainer)region.getUserAttribute();
    TableDescriptor td = container.getTableDescriptor();
    ArrayList<String> cols = new ArrayList<>(2);
    if (td != null) {
      String[] baseColumns = td.getColumnNamesArray();
      GfxdIndexManager im = container.getIndexManager();
      if (im != null && im.getIndexConglomerateDescriptors() != null) {
        for (ConglomerateDescriptor cd : im.getIndexConglomerateDescriptors()) {
          // first column of index has to be present in filter to be usable
          int[] indexCols = cd.getIndexDescriptor().baseColumnPositions();
          cols.add(baseColumns[indexCols[0] - 1]);
        }
      }
      // also add primary key
      ReferencedKeyConstraintDescriptor primaryKey = td.getPrimaryKey();
      if (primaryKey != null) {
        // first column of primary key has to be present in filter to be usable
        int[] pkCols = primaryKey.getKeyColumns();
        if (pkCols != null && pkCols.length > 0) {
          cols.add(baseColumns[pkCols[0] - 1]);
        }
      }
    }
    return cols;
  }

  public static List<String> getPKColumns(LocalRegion region) throws StandardException {
    GemFireContainer container = (GemFireContainer)region.getUserAttribute();
    TableDescriptor td = container.getTableDescriptor();
    ArrayList<String> cols = new ArrayList<>(2);
    if (td != null) {
      String[] baseColumns = td.getColumnNamesArray();
      ReferencedKeyConstraintDescriptor primaryKey = td.getPrimaryKey();
      if (primaryKey != null) {
        int[] pkCols = primaryKey.getKeyColumns();
        if (pkCols != null) {
          for (int pkCol : pkCols) {
            cols.add(baseColumns[pkCol - 1]);
          }
        }
      }
    }
    return cols;
  }

  public static void GET_DEPLOYED_JARS(String[] jarStrings) throws SQLException {
    try {
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "executing GET_DEPLOYED_JARS");
      }
      GfxdListResultCollector collector = new GfxdListResultCollector();
      // ConnectionId is not being used for GET_DEPLOYED_JARS; hence passing dummy value(0L)
      GetLeadNodeInfoAsStringMessage msg = new GetLeadNodeInfoAsStringMessage(
          collector, GetLeadNodeInfoAsStringMessage.DataReqType.GET_JARS, 0L, (Object[])null);
      msg.executeFunction();
      ArrayList<Object> result = collector.getResult();
      String resJarStrings = (String)result.get(0);
      jarStrings[0] = resJarStrings;
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * A recovery mode procedure which allows the user to export the specified(all) tables/views
   * in the specified format at the specified location.
   *
   * @param exportUri
   * @param formatType any format supported by the spark dataframe api
   * @param tableNames comma separated list of fully qualified table names OR all
   * @param ignoreError ignores any exception while querying and exporting any of the tables.
   * @throws SQLException
   */
  // comma separated table names
  public static void EXPORT_DATA(String exportUri, String formatType, String tableNames,
      Boolean ignoreError) throws SQLException {
    try {
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "Executing EXPORT_DATA");
      }
      Long connectionId = Misc.getLanguageConnectionContext().getConnectionId();
      GfxdListResultCollector collector = new GfxdListResultCollector();
      GetLeadNodeInfoAsStringMessage msg = new GetLeadNodeInfoAsStringMessage(
          collector, GetLeadNodeInfoAsStringMessage.DataReqType.EXPORT_DATA, connectionId,
          exportUri, formatType, tableNames, ignoreError);

      msg.executeFunction();
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "EXPORT_DATA successful.");
      }
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * Exports all DDLs to specified directory path
   * @param exportUri complete file path
   * @throws SQLException
   */

  public static void EXPORT_DDLS(String exportUri) throws SQLException  {
    try {
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "Executing EXPORT_DDLS");
      }
      Long connectionId = Misc.getLanguageConnectionContext().getConnectionId();
      GfxdListResultCollector collector = new GfxdListResultCollector();
      GetLeadNodeInfoAsStringMessage msg = new GetLeadNodeInfoAsStringMessage(
          collector, GetLeadNodeInfoAsStringMessage.DataReqType.EXPORT_DDLS, connectionId, exportUri);
      msg.executeFunction();
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "EXPORT_DDLS successful.");
      }
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  public static void GENERATE_LOAD_SCRIPTS() throws SQLException {
    try {
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "Executing GENERATE_LOAD_SCRIPTS");
      }
      Long connectionId = Misc.getLanguageConnectionContext().getConnectionId();
      GfxdListResultCollector collector = new GfxdListResultCollector();
      GetLeadNodeInfoAsStringMessage msg = new GetLeadNodeInfoAsStringMessage(
          collector, GetLeadNodeInfoAsStringMessage.DataReqType.GENERATE_LOAD_SCRIPTS, connectionId);
      msg.executeFunction();
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GENERATE_LOAD_SCRIPTS successful.");
      }
    } catch(StandardException e) {
      throw PublicAPI.wrapStandardException(e);
    }
  }

  /**
   * Create or drop reservoir region for sampler. Note that the creat and drop operation
   * are intentionally combined in single procedure here to make conflation of create and
   * drop operation possible for same region.
   *
   * @param reservoirRegionName name of the reservoir region
   * @param resolvedBaseName base table name with schema
   * @param isDrop flag to indicate that the stored procedure is being invoked to drop the
   *               reservoir region
   */
  public static void CREATE_OR_DROP_RESERVOIR_REGION(String reservoirRegionName,
      String resolvedBaseName, Boolean isDrop) throws SQLException {
    try {
      // check for permission on the sample table schema
      LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
      String currentUser = ((GenericLanguageConnectionContext)lcc).getUserName();
      int dotIndex = resolvedBaseName.indexOf('.');
      if (dotIndex == -1) {
        throw new UnsupportedOperationException(
            "Cannot created reservoir region for base name = " +
                resolvedBaseName + " having no schema");
      }
      String schema = resolvedBaseName.substring(0, dotIndex);
      CallbackFactoryProvider.getStoreCallbacks().checkSchemaPermission(
          schema, currentUser);

      // first create/drop locally
      if (createOrDropReservoirRegion(reservoirRegionName, resolvedBaseName, isDrop)) {
        // don't send to other nodes or persist if local operation is unsuccessful
        final Object[] args = new Object[] { reservoirRegionName,
            resolvedBaseName, isDrop};
        // send to other nodes
        publishMessage(args, false,
            GfxdSystemProcedureMessage.SysProcMethod.createOrDropReservoirRegion,
            true, false);
      }
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  public static boolean createOrDropReservoirRegion(String reservoirRegionName,
      String resolvedBaseName, boolean isDrop) {
    PartitionedRegion existingRegion = Misc.getReservoirRegionForSampleTable(
        reservoirRegionName);
    if (isDrop) {
      // Cached sampler entry needs to be removed from all the nodes even if reservoir region
      // does not exist on that node.
      CallbackFactoryProvider.getStoreCallbacks().removeSampler(resolvedBaseName);
      if (existingRegion != null) {
        existingRegion.destroyRegion(null);
        return true;
      } else {
        return false;
      }
    } else if (existingRegion == null) {
      PartitionedRegion region = Misc.createReservoirRegionForSampleTable(
          reservoirRegionName, resolvedBaseName);
      if (Misc.initialDDLReplayDone()) {
        Assert.assertTrue(region != null);
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * Create all buckets in the given table.
   *
   * @param tableName
   *          the fully qualified table name
   */
  public static void CREATE_ALL_BUCKETS(String tableName) throws SQLException {
    String schema;
    String table;
    int dotIndex;
    // NULL table name is illegal
    if (tableName == null) {
      throw Util.generateCsSQLException(SQLState.ENTITY_NAME_MISSING);
    }

    if ((dotIndex = tableName.indexOf('.')) >= 0) {
      schema = tableName.substring(0, dotIndex);
      table = tableName.substring(dotIndex + 1);
    }
    else {
      schema = Misc.getDefaultSchemaName(ConnectionUtil.getCurrentLCC());
      table = tableName;
    }
    try {
      final GemFireContainer container = CallbackProcedures
          .getContainerForTable(schema, table);
      CREATE_ALL_BUCKETS_INTERNAL(container.getRegion(), tableName);
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  public static void REMOVE_METASTORE_ENTRY(String fqtn, Boolean forceDrop) throws SQLException {
    String schema;
    String table;
    int dotIndex;
    // NULL table name is illegal
    if (fqtn == null) {
      throw Util.generateCsSQLException(SQLState.ENTITY_NAME_MISSING);
    }

    if ((dotIndex = fqtn.indexOf('.')) >= 0) {
      schema = fqtn.substring(0, dotIndex);
      table = fqtn.substring(dotIndex + 1);
    } else {
      schema = Misc.getDefaultSchemaName(ConnectionUtil.getCurrentLCC());
      table = fqtn;
    }
    ExternalCatalog catalog = Misc.getMemStore().getExistingExternalCatalog();
    catalog.removeTableUnsafeIfExists(schema, table, forceDrop);
    CallbackFactoryProvider.getStoreCallbacks().registerCatalogSchemaChange();
  }

  private static void assignBucketsToPartitions(final PartitionedRegion pr) {
    ExecutorService executor = pr.getCache().getDistributionManager()
        .getFunctionExcecutor();
    int numBuckets = pr.getTotalNumberOfBuckets();
    Future<?>[] bucketCreates = new Future[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      final int bucketId = i;
      bucketCreates[i] = executor.submit((Callable<Object>)() -> {
        // this method will return quickly if the bucket already exists
        return pr.createBucket(bucketId, 0, null);
      });
    }
    Throwable failure = null;
    for (int i = 0; i < numBuckets; i++) {
      try {
        bucketCreates[i].get();
      } catch (InterruptedException ie) {
        pr.getCancelCriterion().checkCancelInProgress(ie);
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        pr.getCancelCriterion().checkCancelInProgress(e);
        failure = e.getCause();
      }
    }
    if (failure != null) {
      throw new GemFireXDRuntimeException(failure);
    }
  }

  private static void CREATE_ALL_BUCKETS_INTERNAL(LocalRegion region,
      String tableName) throws SQLException {
    if (region.getAttributes().getPartitionAttributes() != null) {
      // force creation of all buckets in the region
      try {
        assignBucketsToPartitions((PartitionedRegion)region);
        GfxdIndexManager indexManager = (GfxdIndexManager) region.getIndexUpdater();
        if (indexManager != null) {
          List<GemFireContainer> indexContainers = indexManager.getIndexContainers();
            if (indexContainers != null) {
              for (GemFireContainer indexContainer : indexContainers) {
                if (indexContainer.isGlobalIndex()) {
                  assignBucketsToPartitions((PartitionedRegion)indexContainer.getRegion());
                }
              }
            }
        }
        /*
        pr.getRegionAdvisor().accept(new RegionAdvisor.BucketVisitor<Void>() {
          public boolean visit(RegionAdvisor advisor, ProxyBucketRegion pbr,
              Void ignored) {
            pr.getOrCreateNodeForBucketWrite(pbr.getBucketId(), null);
            return true;
          }
        }, null);
        */
      } catch (Throwable t) {
        throw TransactionResourceImpl.wrapInSQLException(t);
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.TABLE_NOT_PARTITIONED,
          tableName, "SYS.CREATE_ALL_BUCKETS");
    }
  }

  private static void getBucketToServerMapping(PartitionedRegion region,
      CatalogTableObject catalogTable) throws SQLException {
    // String servers = GemFireXDUtils.getGfxdAdvisor().getAllNetworkServers();

    Map<Integer, BucketAdvisor> bidToAdvsrMap = region.getRegionAdvisor()
        .getAllBucketAdvisorsHostedAndProxies();
    int totalBuckets = region.getTotalNumberOfBuckets();
    int redundancy = region.getRedundantCopies();
    if (SanityManager.TraceSingleHop || logger.isDebugEnabled()) {
      logger.info("getBucketToServerMapping called for region: " + region.getName()
          + ", with total buckets = " + totalBuckets + " and redundancy = "
          + redundancy + " and bidToAdvsrMap size = " + bidToAdvsrMap.size()
          + ", full map: " + bidToAdvsrMap);
    }
    ArrayList<BucketOwners> allBucketOwners = new ArrayList<>(totalBuckets);
    Map<InternalDistributedMember, String> mbrToServerMap = GemFireXDUtils
        .getGfxdAdvisor().getAllNetServersWithMembers();
    for (Map.Entry<Integer, BucketAdvisor> entry : bidToAdvsrMap.entrySet()) {
      BucketOwners bucketOwners = new BucketOwners(entry.getKey());
      BucketAdvisor advisor = entry.getValue();
      ProxyBucketRegion pbr = advisor.getProxyBucketRegion();
      // throws PartitionOfflineException if appropriate
      try {
        pbr.checkBucketRedundancyBeforeGrab(null, false);
      } catch (Exception e) {
        throw TransactionResourceImpl.wrapInSQLException(e);
      }
      InternalDistributedMember pmbr = advisor.getPrimary();
      Set<InternalDistributedMember> bOwners = pbr.getBucketOwners();
      if (pmbr != null) {
        bOwners.remove(pmbr);
        String primaryServer = mbrToServerMap.get(pmbr);
        if (primaryServer != null && primaryServer.length() > 0) {
          bucketOwners.setPrimary(primaryServer);
        }
      }
      List<String> secondaries = Collections.emptyList();
      for (InternalDistributedMember mbr : bOwners) {
        String secondary = mbrToServerMap.get(mbr);
        if (secondary != null && secondary.length() > 0) {
          if (secondaries.isEmpty()) {
            secondaries = new ArrayList<>(bOwners.size());
          }
          secondaries.add(secondary);
        }
      }
      bucketOwners.setSecondaries(secondaries);
      allBucketOwners.add(bucketOwners);
    }
    catalogTable.setRedundancy(redundancy).setBucketOwners(allBucketOwners);
  }

  /**
   * Get all buckets location information network server addr wise.
   *
   * @param fqtn
   *          the fully qualified table name
   * @param bktToServerMapping
   *          0th index will contain the information in the below format
   *          "numbuckets:redundancy:bucketid1:primarybucketserver;
   *          secondary1bucketserver;...|bucketid2...."
   *          "113:2:0;pc25.pune.gemstone.com/10.112.204.14[25005]{datastore};
   *          null;null|2;pc25.pune.gemstone.com/10.112.204.14[25005]
   *          {datastore};null;null"
   */
  public static void GET_BUCKET_TO_SERVER_MAPPING(String fqtn,
      String[] bktToServerMapping) throws SQLException {
    String schema, table;
    int dotIndex;
    if ((dotIndex = fqtn.indexOf('.')) >= 0) {
      schema = fqtn.substring(0, dotIndex);
      table = fqtn.substring(dotIndex + 1);
    } else {
      schema = Misc.getDefaultSchemaName(ConnectionUtil.getCurrentLCC());
      table = fqtn;
    }

    CatalogTableObject catalogTable = new CatalogTableObject();
    PartitionedRegion region = (PartitionedRegion)Misc.getRegionForTable(
        Misc.getRegionPath(schema, table, null), true);
    getBucketToServerMapping(region, catalogTable);
    StringBuilder bucketInfo = new StringBuilder();
    bucketInfo.append(region.getTotalNumberOfBuckets());
    bucketInfo.append(':');
    bucketInfo.append(catalogTable.getRedundancy());
    bucketInfo.append(':');
    int sz = catalogTable.getBucketOwners().size();
    int cnt = 0;
    for (BucketOwners bucketOwners : catalogTable.getBucketOwners()) {
      cnt++;
      bucketInfo.append(bucketOwners.getBucketId());
      bucketInfo.append(';');
      String primaryServer = bucketOwners.getPrimary();
      if (primaryServer == null) {
        bucketInfo.append("null");
      } else {
        bucketInfo.append(primaryServer);
      }
      int idx = 0;
      for (String secondary : bucketOwners.getSecondaries()) {
        bucketInfo.append(';');
        if (secondary == null) {
          bucketInfo.append("null");
        } else {
          bucketInfo.append(secondary);
        }
        idx++;
      }
      int shortfall = catalogTable.getRedundancy() - idx;
      if (shortfall != 0) {
        for (int i = 0; i < shortfall; i++) {
          bucketInfo.append(";null");
        }
      }
      if (cnt != sz) {
        bucketInfo.append('|');
      }
    }
    bktToServerMapping[0] = bucketInfo.toString();
  }


  /**
   * Get all buckets location information network server addr wise. This
   * updated version uses CLOBs for results so works with large number of
   * buckets that can exceed 32K limit of VARCHARs.
   *
   * @param fqtn
   *          the fully qualified table name
   * @param bktToServerMapping
   *          0th index will contain the information in the below format
   *          "numbuckets:redundancy:bucketid1:primarybucketserver;
   *          secondary1bucketserver;...|bucketid2...."
   *          "113:2:0;pc25.pune.gemstone.com/10.112.204.14[25005]{datastore};
   *          null;null|2;pc25.pune.gemstone.com/10.112.204.14[25005]
   *          {datastore};null;null"
   */
  public static void GET_BUCKET_TO_SERVER_MAPPING2(String fqtn,
      Clob[] bktToServerMapping) throws SQLException {
    String[] mapping = new String[1];
    GET_BUCKET_TO_SERVER_MAPPING(fqtn, mapping);
    if (mapping[0] != null) {
      bktToServerMapping[0] = new HarmonySerialClob(mapping[0]);
    } else {
      bktToServerMapping[0] = null;
    }
  }

  /**
   * Message is published to everybody (including locators) and added to the DDL
   * queue for persistent purposes.
   *
   * Any new member joining will also see the execution of the procedures like
   * statistics enabling/disabling.
   *
   * @param args
   *          arguments of the procedure.
   * @param lastArgServerGroups
   *          if true, then last argument in <code>args</code> is taken as a
   *          serverGroup where the publish will be restricted.
   * @param systemProcedure
   *          procedure method that is remotely invoked.
   * @param persistent whether to include in the DDL queue and replay
   * @param includeLocators should this message published to locator VMs too.
   * @throws SQLException wrapping any StandardExceptions if is raised.
   */
  public static void publishMessage(Object[] args,
      final boolean lastArgServerGroups,
      final GfxdSystemProcedureMessage.SysProcMethod systemProcedure,
      final boolean persistent, final boolean includeLocators)
      throws SQLException, StandardException {
    try {
      LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
      final GemFireCacheImpl cache = Misc.getGemFireCache();
      final DistributedMember myId = cache.getMyId();

      // message needs to be sent to all nodes so that other nodes do not
      // miss it during DDL recovery
      final Set<DistributedMember> members = GfxdMessage.getOtherMembers();
      if (lastArgServerGroups) {
        final SortedSet<String> groups = SharedUtils.toSortedSet(
            (String)args[args.length - 1], false);
        // use a normalized form for persisted server groups argument
        args[args.length - 1] = SharedUtils.toCSV(groups);
      }

      GfxdSystemProcedureMessage msg = new GfxdSystemProcedureMessage(
          systemProcedure, args, lcc != null ? lcc.getCurrentSchemaName()
          : null, 1, 1, myId);

      if (includeLocators) {
        // add locators to the target
        final Set<DistributedMember> locatorMembers = GemFireXDUtils
            .getGfxdAdvisor().adviseServerLocators(true);

        if (locatorMembers != null) {
          members.addAll(locatorMembers);
        }
      }

      members.remove(myId);

      if (GemFireXDUtils.TraceAuthentication) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
            "publishing " + Arrays.toString(args) + " to " + members);
      }

      if (persistent) {
        Misc.getMemStore().getDDLQueueNoThrow().put(msg);
      }
      msg.send(cache.getDistributedSystem(), members, true /*ignoreNodeDown*/);
    } catch (StandardException | SQLException se) {
      throw se;
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  /**
   * Import data from a given file to a table.
   * <p>
   * This version allows specifying some additional parameters to enable/disable
   * table locking, number of threads to be used for import and whether
   * schema/table names are case sensitive or insensitive.
   * <p>
   * Will be called by system procedure as IMPORT_TABLE_EX(IN SCHEMANAME
   * VARCHAR(128), IN TABLENAME VARCHAR(128), IN FILENAME VARCHAR(32672) , IN
   * COLUMNDELIMITER CHAR(1), IN CHARACTERDELIMITER CHAR(1) , IN CODESET
   * VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT, IN NUMTHREADS
   * INTEGER, IN CASESENSITIVENAMES SMALLINT, IN IMPORTCLASSNAME VARCHAR(32672),
   * IN ERRORFILE VARCHAR(32762))
   *
   * @exception SQLException a SQL exception
   */
  public static void IMPORT_TABLE_EX(String schemaName, String tableName,
      String fileName, String columnDelimiter, String characterDelimiter,
      String codeset, short replace, short lockTable, int numThreads,
      short caseSensitiveNames, String importClassName, String errorFile)
      throws SQLException {

    Misc.invalidSnappyDataFeature("IMPORT_TABLE_EX procedure");
    Connection conn = getDefaultConn();
    try {
      // not sure whether this is also a bug in Derby or not,
      // but tableName and schemaName need to be case insensitive.
      // fix for #41412
      // [sumedh] Made it dependent on an additional parameter since the
      // schema/table names can be quoted in the definitions
      if (schemaName == null) {
        LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
        schemaName = lcc.getDefaultSchema().getSchemaName();
      }
      if (schemaName != null && caseSensitiveNames == 0) {
        schemaName = StringUtil.SQLToUpperCase(schemaName);
      }
      if (tableName != null && caseSensitiveNames == 0) {
        tableName = StringUtil.SQLToUpperCase(tableName);
      }
      if (importClassName == null) {
        importClassName = "com.pivotal.gemfirexd.load.Import";
      }
      Import.importTable(conn, schemaName, tableName, fileName,
          columnDelimiter, characterDelimiter, codeset, replace,
          lockTable != 0, numThreads, importClassName, false, errorFile);
    } catch (SQLException se) {
      rollBackAndThrowSQLException(conn, se);
    }
    // import finished successfully, commit it.
    conn.commit();
  }

  /**
   * Import data from a given file into the specified table columns from the
   * specified columns in the file.
   * <p>
   * This version allows specifying some additional parameters to enable/disable
   * table locking, number of threads to be used for import and whether
   * schema/table names are case sensitive or insensitive.
   * <p>
   * Will be called as IMPORT_DATA_EX(IN SCHEMANAME VARCHAR(128), IN TABLENAME
   * VARCHAR(128), IN INSERTCOLUMNLIST VARCHAR(32762), IN COLUMNINDEXES
   * VARCHAR(32762), IN FILENAME VARCHAR(32762), IN COLUMNDELIMITER CHAR(1), IN
   * CHARACTERDELIMITER CHAR(1), IN CODESET VARCHAR(128), IN REPLACE SMALLINT,
   * IN LOCKTABLE SMALLINT, IN NUMTHREADS INTEGER, IN CASESENSITIVENAMES
   * SMALLINT, IN IMPORTCLASSNAME VARCHAR(32672), IN ERRORFILE VARCHAR(32762))
   *
   * @exception SQLException a SQL exception
   */
  public static void IMPORT_DATA_EX(String schemaName, String tableName,
      String insertColumnList, String columnIndexes, String fileName,
      String columnDelimiter, String characterDelimiter, String codeset,
      short replace, short lockTable, int numThreads, short caseSensitiveNames,
      String importClassName, String errorFile) throws SQLException {

    Misc.invalidSnappyDataFeature("IMPORT_DATA_EX procedure");
    Connection conn = getDefaultConn();
    try {
      // tableName and schemaName need to be case insensitive.
      // fix for #41412
      // [sumedh] Made it dependent on an additional parameter since the
      // schema/table names can be quoted in the definitions
      if (schemaName != null && caseSensitiveNames == 0) {
        schemaName = StringUtil.SQLToUpperCase(schemaName);
      }
      if (tableName != null && caseSensitiveNames == 0) {
        tableName = StringUtil.SQLToUpperCase(tableName);
      }
      if (importClassName == null) {
        importClassName = "com.pivotal.gemfirexd.load.Import";
      }
      Import.importData(conn, schemaName, tableName, insertColumnList,
          columnIndexes, fileName, columnDelimiter, characterDelimiter,
          codeset, replace, lockTable != 0, numThreads, importClassName, false,
          errorFile);
    } catch (SQLException se) {
      rollBackAndThrowSQLException(conn, se);
    }

    // import finished successfully, commit it.
    conn.commit();
  }

  /**
   * Import data from a given file to a table. Data for large object columns is
   * in an external file, the reference to it is in the main input file. Read
   * the lob data from the external file using the lob location info in the main
   * import file.
   * <p>
   * This version allows specifying some additional parameters to enable/disable
   * table locking, number of threads to be used for import and whether
   * schema/table names are case sensitive or insensitive.
   * <p>
   * Will be called by system procedure as IMPORT_TABLE_LOBS_FROM_EXTFILE(IN
   * SCHEMANAME VARCHAR(128), IN TABLENAME VARCHAR(128), IN FILENAME
   * VARCHAR(32672) , IN COLUMNDELIMITER CHAR(1), IN CHARACTERDELIMITER CHAR(1)
   * , IN CODESET VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT, IN
   * NUMTHREADS INTEGER, IN CASESENSITIVENAMES SMALLINT, IN IMPORTCLASSNAME
   * VARCHAR(32672), IN ERRORFILE VARCHAR(32762))
   *
   * @exception SQLException a SQL exception
   */
  public static void IMPORT_TABLE_LOBS_FROM_EXTFILE(String schemaName,
      String tableName, String fileName, String columnDelimiter,
      String characterDelimiter, String codeset, short replace,
      short lockTable, int numThreads, short caseSensitiveNames,
      String importClassName, String errorFile) throws SQLException {

    Misc.invalidSnappyDataFeature("IMPORT_TABLE_LOBS_FROM_EXTFILE procedure");
    Connection conn = getDefaultConn();
    try {
      // tableName and schemaName need to be case insensitive.
      // fix for #41412
      // [sumedh] Made it dependent on an additional parameter since the
      // schema/table names can be quoted in the definitions
      if (schemaName != null && caseSensitiveNames == 0) {
        schemaName = StringUtil.SQLToUpperCase(schemaName);
      }
      if (tableName != null && caseSensitiveNames == 0) {
        tableName = StringUtil.SQLToUpperCase(tableName);
      }
      if (importClassName == null) {
        importClassName = "com.pivotal.gemfirexd.load.Import";
      }
      /* lobs in external file */
      Import.importTable(conn, schemaName, tableName, fileName,
          columnDelimiter, characterDelimiter, codeset, replace,
          lockTable != 0, numThreads, importClassName, true, errorFile);
    } catch (SQLException se) {
      rollBackAndThrowSQLException(conn, se);
    }
    // import finished successfully, commit it.
    conn.commit();
  }

  /**
   * Import data from a given file into the specified table columns from the
   * specified columns in the file. Data for large object columns is in an
   * external file, the reference to it is in the main input file. Read the lob
   * data from the external file using the lob location info in the main import
   * <p>
   * This version allows specifying some additional parameters to enable/disable
   * table locking, number of threads to be used for import and whether
   * schema/table names are case sensitive or insensitive. file.
   * <p>
   * Will be called as IMPORT_DATA_LOBS_FROM_EXTFILE(IN SCHEMANAME VARCHAR(128),
   * IN TABLENAME VARCHAR(128), IN INSERTCOLUMNLIST VARCHAR(32762), IN
   * COLUMNINDEXES VARCHAR(32762), IN FILENAME VARCHAR(32762), IN
   * COLUMNDELIMITER CHAR(1), IN CHARACTERDELIMITER CHAR(1), IN CODESET
   * VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT, IN NUMTHREADS
   * INTEGER, IN CASESENSITIVENAMES SMALLINT, IN IMPORTCLASSNAME VARCHAR(32672),
   * IN ERRORFILE VARCHAR(32762))
   */
  public static void IMPORT_DATA_LOBS_FROM_EXTFILE(String schemaName,
      String tableName, String insertColumnList, String columnIndexes,
      String fileName, String columnDelimiter, String characterDelimiter,
      String codeset, short replace, short lockTable, int numThreads,
      short caseSensitiveNames, String importClassName, String errorFile)
      throws SQLException {

    Misc.invalidSnappyDataFeature("IMPORT_DATA_LOBS_FROM_EXTFILE procedure");
    Connection conn = getDefaultConn();
    try {
      // tableName and schemaName need to be case insensitive.
      // fix for #41412
      // [sumedh] Made it dependent on an additional parameter since the
      // schema/table names can be quoted in the definitions
      if (schemaName != null && caseSensitiveNames == 0) {
        schemaName = StringUtil.SQLToUpperCase(schemaName);
      }
      if (tableName != null && caseSensitiveNames == 0) {
        tableName = StringUtil.SQLToUpperCase(tableName);
      }
      if (importClassName == null) {
        importClassName = "com.pivotal.gemfirexd.load.Import";
      }
      /* lobs in external file */
      Import.importData(conn, schemaName, tableName, insertColumnList,
          columnIndexes, fileName, columnDelimiter, characterDelimiter,
          codeset, replace, lockTable != 0, numThreads, importClassName, true,
          errorFile);
    } catch (SQLException se) {
      rollBackAndThrowSQLException(conn, se);
    }

    // import finished successfully, commit it.
    conn.commit();
  }

  /**
   * This procedure sets the log level for either the root logger or a class.
   * If the logClass is empty string, the root logger's level is set.
   */
  public static void SET_LOG_LEVEL(String logClass, String level)
          throws SQLException, StandardException {

    final Object[] params = new Object[] { logClass, level };
    // first process locally
    GfxdSystemProcedureMessage.SysProcMethod.setLogLevel.processMessage(
            params, Misc.getMyId());
    // then publish to other members including locators
    publishMessage(params, false,
            GfxdSystemProcedureMessage.SysProcMethod.setLogLevel, false, true);
  }

  /**
   * This procedure enables a gemfirexd.debug.true trace flag on all members of
   * the DS. The special traceflag DistributionManager.VERBOSE which turns the
   * corresponding GFE layer flag.
   */
  public static void SET_TRACE_FLAG(String traceFlag, Boolean on)
      throws SQLException, StandardException {

    final Object[] params = new Object[] { traceFlag, on };
    // first process locally
    GfxdSystemProcedureMessage.SysProcMethod.setTraceFlag.processMessage(
        params, Misc.getMyId());
    // then publish to other members including locators
    publishMessage(params, false,
        GfxdSystemProcedureMessage.SysProcMethod.setTraceFlag, false, true);
  }

  /**
   * Sets a flag indicating whether FK checks should be done when applying
   * events received from WAN gateway receiver.
   * By default the FK checks are done
   * @param on true if FK checks should be done, false otherwise
   */
  public static void SET_GATEWAY_FK_CHECKS(Boolean on) throws SQLException, StandardException {

    final Object[] params = new Object[] {on};

    GfxdSystemProcedureMessage.SysProcMethod.setGatewayFKChecks
        .processMessage(params, Misc.getMyId());

    publishMessage(params, false,
        GfxdSystemProcedureMessage.SysProcMethod.setGatewayFKChecks, true,
        false);

  }

  /**
   * This procedure is to wait for flushing of the
   * AsyncEventListener/GatewaySender queue.
   *
   * @param id
   *          name of the AsyncEventListener/GatewaySender
   * @param isAsyncListener
   *          true if this is for a AsyncEventListener flush and false if it is
   *          for a GatewaySender queue flush
   * @param maxWaitTime
   *          the maximum time to wait for flush in seconds; a value <= 0 means
   *          block indefinitely until the queue is flushed
   */
  public static void WAIT_FOR_SENDER_QUEUE_FLUSH(String id,
      Boolean isAsyncListener, int maxWaitTime) throws SQLException,
      StandardException {

    final Object[] params = new Object[] { id, isAsyncListener, maxWaitTime };
    // first process locally
    GfxdSystemProcedureMessage.SysProcMethod.waitForSenderQueueFlush
        .processMessage(params, Misc.getMyId());
    // then publish to other members excluding locators
    publishMessage(params, false,
        GfxdSystemProcedureMessage.SysProcMethod.waitForSenderQueueFlush,
        false, false);
  }

  /**
   * Get the given table's current schema version. This is 1 after initial
   * CREATE TABLE and increases by 1 for every ALTER TABLE ADD/DROP COLUMN.
   *
   * @param schemaName
   *          the schema of the table
   * @param tableName
   *          the name of the table without schema
   *
   * @return the current version of the table's schema, or -1 if the table does
   *         not support schema versions
   *
   * @see #INCREMENT_TABLE_VERSION(String, String, int)
   */
  public static int GET_TABLE_VERSION(String schemaName, String tableName)
      throws SQLException {
    try {
      GemFireContainer container = GemFireXDUtils.getGemFireContainer(schemaName,
          tableName, null);
      if (container == null) {
        throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND,
            Misc.getFullTableName(schemaName, tableName, null));
      }
      return container.getCurrentSchemaVersion();
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  /**
   * Force increment the given table's schema version by given amount. This
   * should only be used to force match the version numbers across WAN sites if
   * there has been an inadvertant schema version mismatch even though final
   * schema is identical.
   *
   * @param schemaName
   *          the schema of the table
   * @param tableName
   *          the name of the table without schema
   * @param increment
   *          the increment required in the table's schema version; should be >0
   *
   * @see #GET_TABLE_VERSION(String, String)
   */
  public static void INCREMENT_TABLE_VERSION(String schemaName,
      String tableName, int increment) throws SQLException, StandardException {

    if (increment <= 0) {
      throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
          "decrementing table version by " + increment);
    }

    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    try {
      schemaName = Misc.getSchemaName(schemaName, lcc);
      ddLocked = lcc.getDataDictionary().lockForWriting(tc, false);

      final Object[] params = new Object[] { schemaName, tableName, increment };
      // first process locally
      GfxdSystemProcedureMessage.SysProcMethod.incrementTableVersion
          .processMessage(params, Misc.getMyId());
      // then publish to other members excluding locators
      publishMessage(params, false,
          GfxdSystemProcedureMessage.SysProcMethod.incrementTableVersion, true,
          false);

    } catch (StandardException se) {
      throw se;
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    } finally {
      if (ddLocked) {
        lcc.getDataDictionary().unlockAfterWriting(tc, false);
      }
    }
  }

  /**
   * Flush and fsync disk store data to disk on all members of the cluster.
   *
   * @param diskStoreName
   *          name of the disk store to flush and fsync to disk; if null then
   *          all disk stores are fsynced
   */
  public static void DISKSTORE_FSYNC(String diskStoreName) throws SQLException,
      StandardException {

    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    try {
      ddLocked = lcc.getDataDictionary().lockForWriting(tc, false);

      final Object[] params = new Object[] { diskStoreName };
      // first process locally
      GfxdSystemProcedureMessage.SysProcMethod.diskStoreFsync.processMessage(
          params, Misc.getMyId());
      // then publish to other members excluding locators
      publishMessage(params, false,
          GfxdSystemProcedureMessage.SysProcMethod.diskStoreFsync, false, false);

    } catch (StandardException se) {
      throw se;
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    } finally {
      if (ddLocked) {
        lcc.getDataDictionary().unlockAfterWriting(tc, false);
      }
    }
  }

  public static void FIX_PREVIOUS_OPS_COUNT(String tableName) {
    Region region = Misc.getRegionForTable(tableName, true);
    if (region != null) {
      if (region instanceof PartitionedRegion) {
        PartitionedRegion pr = (PartitionedRegion)region;
        for (BucketRegion br : pr.getDataStore().getAllLocalBucketRegions()) {
          br.getBucketAdvisor().resetPrevOpCount();
        }
      } else if (region instanceof DistributedRegion) {
        ((DistributedRegion)region).getDistributionAdvisor().resetPrevOpCount();
      }
    }
  }

  /**
   * This procedure dumps the thread stacks, locks, transaction stats of current
   * node to log file. It is identical to sending SIGURG on UNIX systems. The
   * optional boolean flag also sends it to all the nodes in the cluster.
   */
  public static void DUMP_STACKS(Boolean all)
      throws SQLException, StandardException {

    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    final Object[] params = new Object[] { lcc.getAuthorizationId() };
    // first process locally
    GfxdSystemProcedureMessage.SysProcMethod.dumpStacks.processMessage(params,
        Misc.getMyId());
    if (Boolean.TRUE.equals(all)) {
      // publish to other members including locators
      publishMessage(params, false,
          GfxdSystemProcedureMessage.SysProcMethod.dumpStacks, false, true);
    }
  }

  /**
   * This procedure sets the local execution mode for a particular bucket.
   */
  public static void setBucketsForLocalExecution(String tableName,
      Set<Integer> bucketSet, boolean retain,
      @Nonnull LanguageConnectionContext lcc) {
    Region region = Misc.getRegionForTable(tableName, true);
    lcc.setExecuteLocally(bucketSet, region, false, null);
    lcc.setBucketRetentionForLocalExecution(retain);

  }

  /**
   * This procedure sets the local execution mode for a particular bucket.
   * To prevent clearing of lcc in case of thin client connections a flag
   * BUCKET_RENTION_FOR_LOCAL_EXECUTION is set.
   */
  public static void SET_BUCKETS_FOR_LOCAL_EXECUTION(String tableName,
      String buckets, long catalogSchemaVersion)
      throws SQLException, StandardException {
    if (tableName == null) {
      throw Util.generateCsSQLException(SQLState.ENTITY_NAME_MISSING);
    }

    ExternalCatalog catalog = Misc.getMemStore().getExistingExternalCatalog();
    final long actualVersion = catalog.getCatalogSchemaVersion();

    if ((catalogSchemaVersion != -1) &&
        (actualVersion != catalogSchemaVersion)) {
      throw StandardException.newException(SQLState.SNAPPY_CATALOG_SCHEMA_VERSION_MISMATCH,
          actualVersion, catalogSchemaVersion);
    }

    Region region = Misc.getRegionForTable(tableName, true);
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    Set<Integer> bucketSet = new UnifiedSet<>();
    StringTokenizer st = new StringTokenizer(buckets,",");
    while(st.hasMoreTokens()){
      bucketSet.add(Integer.parseInt(st.nextToken()));
    }
    setBucketsForLocalExecution(tableName, bucketSet, true, lcc);
  }


  /**
   * This procedure sets the Nanotimer type. NanoTimer are used extensively while
   * generating the Explain plans. The timer can either be set to use
   * Java's java.lang.System.nanoTime() or to make a native call to get the nanoTime.
   * If native timer is to be used, the native timer type can be specified as one of these:
   *  CLOCK_REALTIME;
   *  CLOCK_MONOTONIC;
   *  CLOCK_PROCESS_CPUTIME_ID;
   *  CLOCK_THREAD_CPUTIME_ID;
   *  CLOCK_MONOTONIC_RAW;
   * <p>
   * Java's java.lang.System.nanoTime() is much faster than native timer calls.
   */
  public static void SET_NANOTIMER_TYPE(Boolean useNativeTimer, String nativeTimerType)
      throws SQLException, StandardException {

    final Object[] params = new Object[] { useNativeTimer, nativeTimerType };
    // first process locally
    GfxdSystemProcedureMessage.SysProcMethod.setNanoTimerType.processMessage(
        params, Misc.getMyId());
    // then publish to other members including locators
    publishMessage(params, false,
        GfxdSystemProcedureMessage.SysProcMethod.setNanoTimerType, false, true);
  }

  // register the call backs with the JDBCSource so that
  // bucket region can insert into the column table
  public static void flushLocalBuckets(String resolvedName, boolean forceFlush) {
    PartitionedRegion pr = (PartitionedRegion)Misc.getRegionForTable(
        resolvedName, false);
    PartitionedRegionDataStore ds;
    if (pr != null && (ds = pr.getDataStore()) != null) {
      TXStateInterface tx = pr.getTXState();
      for (BucketRegion bucketRegion : ds.getAllLocalPrimaryBucketRegions()) {
        if (forceFlush || bucketRegion.checkForColumnBatchCreation(null)) {
          bucketRegion.createAndInsertColumnBatch(tx, forceFlush);
        }
      }
    }
  }

  public static Boolean ACQUIRE_REGION_LOCK(String lockName, int timeout)
          throws SQLException {
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    GemFireTransaction tr = (GemFireTransaction) lcc.getTransactionExecute();
    PartitionedRegion.RegionLock lock = PartitionedRegion.getRegionLock
            (lockName, GemFireCacheImpl.getExisting());
    if (GemFireXDUtils.TraceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
              "in procedure ACQUIRE_REGION_LOCK() for lockName:  " + lockName
                      + " timeout=" + timeout);
    }
    try {
      lock.lock(timeout);
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
    if (lock != null)
      tr.addTableLock(lock);

    return true;
  }

  public static Boolean RELEASE_REGION_LOCK(String lockName)
      throws SQLException {
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    GemFireTransaction tr = (GemFireTransaction) lcc.getTransactionExecute();
    PartitionedRegion.RegionLock lock = tr.getRegionLock(lockName);
    if (GemFireXDUtils.TraceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
              "in procedure RELEASE_REGION_LOCK() for lockName:  " + lockName);
    }
    if (lock != null) {
      try {
        lock.unlock();
      } catch (Throwable t) {
        throw TransactionResourceImpl.wrapInSQLException(t);
      }
      tr.removeTableLock(lock);
    }
    // we should ignore exceptions.
    return true;
  }

  public static void COMMIT_SNAPSHOT_TXID(String txId, String rolloverTable)
      throws SQLException {
    try {
      LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
      GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();

      if (GemFireXDUtils.TraceExecution) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
            "in procedure COMMIT_SNAPSHOT_TXID()  " + txId
                + " rolloverTable=" + rolloverTable + " for connid "
                + tc.getConnectionID() + " rolloverTable=" + rolloverTable
                + " TxManager " + TXManagerImpl.getCurrentTXId()
                + " snapshot tx = " + TXManagerImpl.getCurrentSnapshotTXState());
      }
      boolean success = false;
      try {
        if (!rolloverTable.isEmpty()) {
          flushLocalBuckets(rolloverTable, false);
        }
        success = true;
      } finally {
        if (success) {
          commitSnapShotTXId(txId, lcc, tc);
        } else {
          rollbackSnapshotTXId(txId, lcc, tc);
        }
      }
    } catch (SQLException sqle) {
      throw sqle;
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  private static void commitSnapShotTXId(String txId,
      LanguageConnectionContext lcc, GemFireTransaction tc) {
    TXStateInterface txState = null;
    TXManagerImpl txManager = tc.getTransactionManager();

    if (!txId.isEmpty()) {
      StringTokenizer st = new StringTokenizer(txId, ":");

      long memberId = Long.parseLong(st.nextToken());
      int uniqId = Integer.parseInt(st.nextToken());
      TXId txId1 = TXId.valueOf(memberId, uniqId);

      txState = txManager.getHostedTXState(txId1);
    }

    tc.clearActiveTXState(false, true);
    lcc.clearExecuteLocally();
    // this is being done because txState is being shared across conn
    if (txState != null && txState.isInProgress()) {
      txManager.masqueradeAs(txState);
      txManager.commit();
    } else {
      TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
      txState = context != null ? context.getSnapshotTXState() : null;
      if (txState != null) {
        txManager.removeHostedTXState(txState.getTransactionId(), Boolean.TRUE);
      }
      if (GemFireXDUtils.TraceExecution) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
            "in procedure COMMIT_SNAPSHOT_TXID()  afer commit" + txId + " for connid " + tc.getConnectionID()
                + " TxManager " + TXManagerImpl.getCurrentTXId()
                + " snapshot tx : " + txState + " else part. ");
      }
      if (context != null) {
        context.clearTXStateAll();
      }
    }
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "in procedure COMMIT_SNAPSHOT_TXID()  afer commit" + txId + " for connid " + tc.getConnectionID()
              + " TxManager " + TXManagerImpl.getCurrentTXId()
              + " snapshot tx : " + txState);
    }
  }

  public static void ROLLBACK_SNAPSHOT_TXID(String txId) throws SQLException {
    try {
      LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
      GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();

      rollbackSnapshotTXId(txId, lcc, tc);
    } catch (SQLException sqle) {
      throw sqle;
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  private static void rollbackSnapshotTXId(String txId,
      LanguageConnectionContext lcc, GemFireTransaction tc) {
    TXStateProxy txState = null;
    TXManagerImpl.TXContext context;
    TXManagerImpl txManager = tc.getTransactionManager();

    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "in procedure ROLLBACK_SNAPSHOT_TXID()  " + txId + " for connid " + tc.getConnectionID()
              + " TxManager " + TXManagerImpl.getCurrentTXId()
              + " snapshot tx : " + TXManagerImpl.getCurrentSnapshotTXState());
    }

    if (!txId.isEmpty()) {
      StringTokenizer st = new StringTokenizer(txId, ":");

      long memberId = Long.parseLong(st.nextToken());
      int uniqId = Integer.parseInt(st.nextToken());
      TXId txId1 = TXId.valueOf(memberId, uniqId);

      txState = txManager.getHostedTXState(txId1);
    }

    tc.clearActiveTXState(false, true);
    lcc.clearExecuteLocally();
    // this is being done because txState is being shared across conn
    if (txState != null && txState.isInProgress()) {
      txManager.masqueradeAs(txState);
      txManager.rollback();
    } else if ((context = TXManagerImpl.currentTXContext()) != null) {
      context.clearTXStateAll();
    }
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "in procedure ROLLBACK_SNAPSHOT_TXID()  afer commit" + txId + " for connid " + tc.getConnectionID()
              + " TxManager " + TXManagerImpl.getCurrentTXId()
              + " snapshot tx : " + TXManagerImpl.getCurrentSnapshotTXState());
    }
  }

  public static String GET_SNAPSHOT_TXID_AND_HOSTURL(Boolean delayRollover)
      throws SQLException {
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();
    TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
    TXStateInterface tx = context != null ? context.getSnapshotTXState() : null;
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "in function GET_SNAPSHOT_TXID_AND_HOSTURL()  for conn " + tc.getConnectionID()
      + " delayRollover=" + delayRollover + " tc id" + tc.getTransactionIdString()
      + " TxManager " + TXManagerImpl.getCurrentTXId()
      + " snapshot tx : " + tx);
    }

    String txIdAndHostUrl;
    if (tx != null && !tx.isClosed()) {
      tx.getProxy().setColumnRolloverDisabled(delayRollover);
      txIdAndHostUrl = tx.getTransactionId().stringFormat() + '@' +
          GemFireXDUtils.getGfxdAdvisor().getOwnNetServers();
    } else {
      txIdAndHostUrl = "@";
    }
    // tc commit will clear all the artifacts but will not commit actual txState
    // that should be committed in COMMIT procedure
    tc.clearActiveTXState(true, true);
    //tc.resetActiveTXState(true);
    if (context != null) {
      context.setSnapshotTXState(null);
    }
    return txIdAndHostUrl;
  }

  public static void START_SNAPSHOT_TXID(Boolean delayRollover, String[] txid)
      throws SQLException {
    TXManagerImpl txManager = Misc.getGemFireCache().getCacheTransactionManager();
    TXManagerImpl.TXContext context = TXManagerImpl.getOrCreateTXContext();
    // rollback any previous transaction (e.g. after task kill)
    final TXStateInterface oldTX = context.getTXState();
    if (oldTX != null) {
      try {
        txManager.rollback(oldTX, null, false);
      } catch (TransactionException ignored) {
      }
    }
    context.clearTXStateAll();
    final TXStateInterface tx = txManager.beginTX(context,
        IsolationLevel.SNAPSHOT, null, null);
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();
    tc.setActiveTXState(tx, false);

    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "in procedure START_SNAPSHOT_TXID()  for conn " + tc.getConnectionID()
              + " delayRollover=" + delayRollover
              + " tc id" + tc.getTransactionIdString()
              + " TxManager " + TXManagerImpl.getCurrentTXId()
              + " snapshot tx : " + tx);
    }

    if (!tx.isClosed()) {
      tx.getProxy().setColumnRolloverDisabled(delayRollover);
      txid[0] = tx.getTransactionId().stringFormat();
    } else {
      txid[0] = "";
    }
    /*// tc commit will clear all the artifacts but will not commit actual txState
    // that should be committed in COMMIT procedure*/
    // start may be called on different conn
    //tc.resetActiveTXState(true);
    //TXManagerImpl.getOrCreateTXContext().clearTXState();
    //TXManagerImpl.snapshotTxState.set(null);
  }

  public static void USE_SNAPSHOT_TXID(String txId) throws SQLException {
    LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
    useSnapshotTXId(txId, lcc);
  }

  public static void useSnapshotTXId(String txId,
      LanguageConnectionContext lcc) throws SQLException {
    int splitAt = txId.indexOf(':');
    if (splitAt == -1) {
      throw PublicAPI.wrapStandardException(StandardException.newException(
          SQLState.GFXD_TRANSACTION_ILLEGAL, "Invalid snapshot transaction ID = " + txId));
    }
    long memberId = Long.parseLong(txId.substring(0, splitAt));
    int uniqId = Integer.parseInt(txId.substring(splitAt + 1));
    TXId txId1 = TXId.valueOf(memberId, uniqId);
    GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();
    TXManagerImpl txManager = tc.getTransactionManager();
    TXStateProxy state = txManager.getHostedTXState(txId1);
    TXManagerImpl.TXContext context = TXManagerImpl.getOrCreateTXContext();

    if (state == null) {
      if (GemFireXDUtils.TraceExecution) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
            "In useSnapshotTXId() creating a txState for conn " +
                tc.getConnectionID() + " tc id" + tc.getTransactionIdString() +
                " for " + txId1.shortToString());
      }
      // if state is null then create txstate and use
      state =  txManager.getOrCreateHostedTXState(txId1,
          com.gemstone.gemfire.internal.cache.locks.LockingPolicy.SNAPSHOT, true);
    }
    txManager.setTXState(state, context);
    context.setSnapshotTXState(state);
    tc.setActiveTXState(state, false);
    // If already then throw exception?
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "In useSnapshotTXId() for txid " + txId1 +
              " txState : " + state + " connId" + tc.getConnectionID());
    }
  }

  /**
   * Get whether the NanoTimer is internally making a native call to get the nanoTime.
   */
  public static Boolean GET_IS_NATIVE_NANOTIMER() {
    return NanoTimer.getIsNativeTimer();
  }

  /**
   * Get the type of the native NanoTimer being used.
   */
  public static String GET_NATIVE_NANOTIMER_TYPE() {
    return NanoTimer.getNativeTimerType();
  }

  /**
   * This procedure checks whether catalog is consistent by comparing Hive meta store
   * with Store data dictionary and optionally repairs the catalog (Hive MetaStore and
   * data dictionary) by removing inconsistent entries in the catalog. By default this procedure
   * will add warning messages in the log file for inconsistent entries and currently does not
   * print anything on console
   *
   * @param removeInconsistentEntries if true remove inconsistent entries from catalog
   * @param removeTablesWithData remove entries for tables even if those contain data (by default not removed)
   */
  public static void REPAIR_CATALOG(Boolean removeInconsistentEntries, Boolean removeTablesWithData)
      throws SQLException, StandardException {
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "in procedure REPAIR_CATALOG() removeInconsistentEntries=" +
              removeInconsistentEntries + " removeTablesWithData=" + removeTablesWithData);
    }
    runCatalogConsistencyChecks(removeInconsistentEntries, removeTablesWithData);
  }

  private static void runCatalogConsistencyChecks(boolean removeInconsistentEntries, boolean removeTablesWithData)
      throws SQLException, StandardException {
    try (EmbedConnection conn = GemFireXDUtils.createNewInternalConnection(false)) {
      FabricDatabase.checkSnappyCatalogConsistency(conn, removeInconsistentEntries, removeTablesWithData);
      CallbackFactoryProvider.getStoreCallbacks().registerCatalogSchemaChange();
    }
  }

  /**
   * Cancel a statement asynchronously on all nodes i.e. this will not wait for
   * the response of cancel message.
   *
   * @param statementUUID
   *          A UUID of the statement.<br>
   * <br>
   *          This can be obtained by querying the SYS.SESSIONS table. A UUID of
   *          a statement is of the form ConncetionID-StatementID-ExecutionID.
   *          If ExecutionID is 0 in the in the statement UUID, it will be
   *          ignored and the statement that has matching ConncetionID and
   *          StatementID will be cancelled<br>
   */
  public static void CANCEL_STATEMENT(String statementUUID)
      throws StandardException {
    if ((statementUUID == null) ||
        !(statementUUID.matches("[0-9]+-[0-9]+-[0-9]+"))) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT, statementUUID,
          "CANCEL_STATEMENT");
    }
    String s[] = statementUUID.split("-");
    long connectionId = Long.parseLong(s[0]);
    long statementId = Long.parseLong(s[1]);
    long executionID = Long.parseLong(s[2]);

    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "CANCEL_STATEMENT connectionId=" + connectionId + " statementId="
              + statementId + " executionID=" + executionID);
    }
    // send a message to cancel the query on all data nodes
    QueryCancelFunctionArgs args = QueryCancelFunction
        .newQueryCancelFunctionArgs(statementId, connectionId);
    Set<DistributedMember> otherMembers = GfxdMessage.getAllGfxdServers();
    if (otherMembers.size() > 0) {
      FunctionService.onMembers(otherMembers).withArgs(args).execute(
          QueryCancelFunction.ID);
    }
  }

  /**
   * Checks consistency of indexes(local and global) on the given table
   *
   * @return returns 1 when indexes are consistent, otherwise
   * throws exception
   */
  public static int CHECK_TABLE_EX(String schema, String table) throws
      SQLException, StandardException, InterruptedException {
    if (schema == null || table == null) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT, "NULL",
          "CHECK_TABLE_EX");
    }
    final Object[] params;

    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "CHECK_TABLE_EX schema:" + schema + "table: " + table);
    }

    // just add any one data store member id as 3rd param on which
    // we will verify global index region size with base table size
    final boolean isStore = ServerGroupUtils.isDataStore();
    if (isStore) {
      params = new Object[]{schema, table, Misc.getMyId()};
    } else {
      Set<DistributedMember> dataStores = GfxdMessage.getAllDataStores();
      DistributedMember targetNode = dataStores.iterator().next();
      params = new Object[]{schema, table, targetNode};
    }

    Thread thread = null;
    final StandardException[] failure = new StandardException[1];
    try {
      // execute on self in a different thread as this procedure might be time
      // consuming and then send message to other nodes in parallel to execute
      if (isStore) {
        thread = new Thread(() -> {
          try {
            GfxdSystemProcedureMessage.SysProcMethod.
                checkTableEx.processMessage(params, Misc.getMyId());
          } catch (StandardException s) {
            failure[0] = s;
          }
        }, "CHECK_TABLE_EX sys proc executor");
        thread.start();
      }
      // send message to other nodes
      publishMessage(params, false,
          GfxdSystemProcedureMessage.SysProcMethod.checkTableEx, false, false);
    } finally {
      if (thread != null) {
        thread.join();
      }
    }
    if (failure[0] != null) {
      throw failure[0];
    }
    return 1;
  }

  /**
   * Refresh LDAP group permissions for all relevant tables on all nodes.
   *
   * @param ldapGroup
   *          A LDAP group to be refreshed
   *          <p>
   *          LDAP groups are associated with table/routine permissions using
   *          GRANT/REVOKE with grantee as "ldapGroup:{group}".
   *          <p>
   *
   * @throws SQLException
   *           on error in distribution to other nodes
   */
  public static void REFRESH_LDAP_GROUP(String ldapGroup) throws SQLException {
    if (ldapGroup == null) {
      throw PublicAPI.wrapStandardException(StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT, null, "REFRESH_LDAP_GROUP"));
    }

    ldapGroup = StringUtil.SQLToUpperCase(ldapGroup);
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "REFRESH_LDAP_GROUP ldapGroup=" + ldapGroup);
    }

    // lookup the current members for the LDAP group
    final LDAPAuthenticationSchemeImpl ldapAuth;
    final Set<String> currentMembers;
    try {
      AuthenticationServiceBase authService = Misc.getMemStoreBooting()
          .getDatabase().getAuthenticationService();
      if (authService == null) {
        throw new javax.naming.NameNotFoundException(
            "Require LDAP authentication scheme for "
                + "LDAP group support no authentication is disabled");
      }
      UserAuthenticator auth = authService.getAuthenticationScheme();
      if (auth instanceof LDAPAuthenticationSchemeImpl) {
        ldapAuth = (LDAPAuthenticationSchemeImpl)auth;
      } else {
        throw new javax.naming.NameNotFoundException(
            "Require LDAP authentication scheme for "
                + "LDAP group support but is " + auth);
      }
      currentMembers = ldapAuth.getLDAPGroupMembers(ldapGroup);
    } catch (javax.naming.NamingException ne) {
      throw PublicAPI.wrapStandardException(StandardException
          .newException(SQLState.AUTH_INVALID_LDAP_GROUP, ne, ldapGroup));
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }

    // lock the DataDictionary for writing
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    TransactionController tc = lcc.getTransactionExecute();
    boolean ddLocked = false;
    try {
      ddLocked = lcc.getDataDictionary().lockForWriting(tc, false);
      // make a copy of currentMembers since it gets modified in
      // refreshLdapGroup call below
      final UnifiedSet<String> origMembers = new UnifiedSet<>(currentMembers);
      // refresh the LDAP groups on this node first
      final Object[] params = new Object[] { ldapGroup, currentMembers };
      GfxdSystemProcedureMessage.SysProcMethod.refreshLdapGroup
          .processMessage(params, Misc.getMyId());
      // send a message to refresh the LDAP group information on all nodes
      params[1] = origMembers;
      publishMessage(params, false, GfxdSystemProcedureMessage.SysProcMethod
          .refreshLdapGroup, false, false);
      // clear any existing pooled connections to force authentication to happen afresh
      CallbackFactoryProvider.getStoreCallbacks().clearConnectionPools();
    } catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    } finally {
      if (ddLocked) {
        try {
          lcc.getDataDictionary().unlockAfterWriting(tc, false);
        } catch (StandardException se) {
          SanityManager.DEBUG_PRINT("warning:EXCEPTION",
              "Failed to unlock DataDictionary for writing", se);
        }
      }
    }
  }

  /**
   * Get the schema for a column table as a JSON string (as in Spark SQL).
   *
   * @param schema name
   * @param table The  name of column table.
   * @throws SQLException if table is not found or is not a column table
   */
  public static void GET_COLUMN_TABLE_SCHEMA(String schema, String table,
      Clob[] schemaAsJson) throws SQLException {

    String schemaString = Misc.getMemStore().getExistingExternalCatalog()
        .getColumnTableSchemaAsJson(schema, table);
    if (schemaString == null) {
      throw PublicAPI.wrapStandardException(StandardException.newException(
          SQLState.TABLE_NOT_FOUND, table));
    }
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          "GET_COLUMN_TABLE_SCHEMA table=" + table + " schema=" + schemaString);
    }
    schemaAsJson[0] = new HarmonySerialClob(schemaString);
  }

  private static final SharedUtils.CSVVisitor<TIntArrayList, Void> projectionAgg =
      (str, projection, context) -> projection.add(Integer.parseInt(str.trim()));

  private static final ResultColumnDescriptor[] columnScanInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor("UUID",
          Types.BIGINT, false),
      EmbedResultSetMetaData.getResultColumnDescriptor("BUCKETID",
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor("COLUMNPOSITION",
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor("DATA",
          Types.BLOB, false)
  };

  public static void COLUMN_TABLE_SCAN(String columnTable, String projection,
      Blob filters, ResultSet[] result) throws SQLException {
    try {
      // split the projection into column indexes (1-based)
      final TIntArrayList columnsList = new TIntArrayList(4);
      SharedUtils.splitCSV(projection, projectionAgg, columnsList, null);

      // check authorization for given columns of the table
      LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
      int[] columns = columnsList.toNativeArray();
      String rowBufferTable = GemFireContainer.getRowBufferTableName(columnTable);
      authorizeTableOperation(lcc, rowBufferTable, columns,
          Authorizer.SELECT_PRIV, Authorizer.SQL_SELECT_OP);

      byte[] batchFilters = null;
      if (filters != null) {
        batchFilters = filters.getBytes(1, (int)filters.length());
        filters.free();
      }
      Set<Integer> bucketIds = lcc.getBucketIdsForLocalExecution();
      final CloseableIterator<ColumnTableEntry> iter =
          CallbackFactoryProvider.getStoreCallbacks().columnTableScan(
              columnTable, columns, batchFilters, bucketIds);
      if (GemFireXDUtils.TraceExecution) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
            "COLUMN_TABLE_SCAN table=" + columnTable +
                " projection=" + projection);
      }
      result[0] = new CustomRowsResultSet(new CustomRowsResultSet.FetchDVDRows() {
        @Override
        public boolean getNext(DataValueDescriptor[] template)
            throws StandardException {
          if (iter.hasNext()) {
            ColumnTableEntry entry = iter.next();
            template[0].setValue(entry.uuid);
            template[1].setValue(entry.bucketId);
            template[2].setValue(entry.columnPosition);
            ClientBlob blob = new ClientBlob(entry.columnValue);
            // mark chunk as having a reference set from outside (columnTableScan)
            if (!blob.getCurrentChunk().initChunkFromReference()) {
              throw StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION,
                  new IllegalStateException("failed to initialize chunk with buffer"));
            }
            template[3].setValue(blob);
            return true;
          } else {
            return false;
          }
        }

        @Override
        public void close() {
          iter.close();
        }
      }, columnScanInfo);
    } catch (SQLException se) {
      throw se;
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
  }

  /**
   * Check SELECT authorization for given columns of a column or row table.
   * The parameter "authType" must be one of the Authorizer.*PRIV types while
   * "opType" must be one of the Authorizer.*OP types.
   */
  public static void authorizeTableOperation(LanguageConnectionContext lcc,
      String tableName, int[] columns, int authType, int opType)
      throws StandardException {
    if (lcc.usesSqlAuthorization()) {
      final int numColumns = columns.length;
      ArrayList<StatementPermission> permissions = new ArrayList<>(
          numColumns + 1);
      GemFireContainer rowContainer = (GemFireContainer)Misc.getRegionForTable(
          tableName, true).getUserAttribute();
      TableDescriptor td = rowContainer.getTableDescriptor();
      if (td == null) {
        throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND,
            tableName);
      }
      permissions.add(new StatementTablePermission(td.getUUID(), authType));
      if (numColumns > 0) {
        FormatableBitSet bitSet = new FormatableBitSet(td.getNumberOfColumns());
        for (int col : columns) {
          ColumnDescriptor cd = td.getColumnDescriptor(col);
          if (cd == null) {
            throw StandardException.newException(SQLState.LANG_COLUMN_NOT_FOUND,
                tableName + '.' + col);
          }
          bitSet.set(col - 1);
        }
        permissions.add(new StatementColumnPermission(td.getUUID(),
            authType, bitSet));
      }
      lcc.getAuthorizer().authorize(null, null, permissions, opType);
    }
  }

  /**
   * Get the default or nested connection corresponding to the URL
   * jdbc:default:connection. We do not use DriverManager here as it is not
   * supported in JSR 169. IN addition we need to perform more checks for null
   * drivers or the driver returing null from connect as that logic is in
   * DriverManager.
   *
   * @return The nested connection
   * @throws SQLException
   *           Not running in a SQL statement
   */
  private static Connection getDefaultConn() throws SQLException {
    InternalDriver id = InternalDriver.activeDriver();
    if (id != null) {
      Connection conn = id.connect("jdbc:default:connection", null);
      if (conn != null)
        return conn;
    }
    throw Util.noCurrentConnection();
  }

  /**
   * issue a rollback when SQLException se occurs. If SQLException ouccurs when
   * rollback, the new SQLException will be added into the chain of se.
   */
  private static void rollBackAndThrowSQLException(Connection conn,
      SQLException se) throws SQLException {
    try {
      conn.rollback();
    } catch (SQLException e) {
      se.setNextException(e);
    }
    throw se;
  }
}
