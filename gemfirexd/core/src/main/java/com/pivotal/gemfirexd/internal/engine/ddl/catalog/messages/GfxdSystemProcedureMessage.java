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
package com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.internal.GFToSlf4jBridge;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.internal.catalog.SystemProcedures;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLPreprocess;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.CallbackProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.AbstractGfxdReplayableMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColPermsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.PermissionsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.RoutinePermsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TablePermsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSROUTINEPERMSRowFactory;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSTABLEPERMSRowFactory;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ConstantActionActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.RoutinePrivilegeInfo;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TablePrivilegeInfo;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;

/**
 * System Procedure Message that is sent out to members to execute procedures
 * remotely. This is different from Data Aware procedures in remote nodes query
 * string is not re-compiled and executed via derby's path instead underlying
 * function is directly invoked.
 * 
 * @author soubhikc
 * 
 */
public final class GfxdSystemProcedureMessage extends
    AbstractGfxdReplayableMessage implements GfxdDDLPreprocess {

  private static final long serialVersionUID = 2039841562674551814L;

  protected static final short HAS_SERVER_GROUPS = UNRESERVED_FLAGS_START;
  protected static final short INITIAL_DDL_REPLAY_IN_PROGRESS =
      (HAS_SERVER_GROUPS << 1);

  public enum SysProcMethod {

    setDatabaseProperty {

      @Override
      boolean allowExecution(Object[] params) {
        // some of the DB properties may need to be set on LOCATORs etc. also,
        // so allowing on all since it does not hurt in any case
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert String.class.isInstance(params[0])
            && (String.class.isInstance(params[1]) || params[1] == null);

        String key = (String)params[0];
        String value = (String)params[1];
        Misc.getMemStoreBooting().setProperty(key, value, false);

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "configured (" + key + "," + value
                  + ") for request originated from " + sender);
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        int numPrms = InternalDataSerializer.readArrayLength(in);
        assert numPrms == 2;

        Object[] inParams = new Object[numPrms];
        for (int count = 0; count < numPrms; count++) {
          inParams[count] = InternalDataSerializer.readString(in);
        }

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        assert (params != null && params.length == 2);

        InternalDataSerializer.writeArrayLength(params.length, out);
        for (Object prm : params) {
          assert prm == null || String.class.isInstance(prm);
          InternalDataSerializer.writeString((String)prm, out);
        }
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return true;
      }

      @Override
      Object[] merge(Object[] params, SysProcMethod existingMethod,
          Object[] existingMethodParams) {
        if (existingMethod == setDatabaseProperty && params[0] != null
            && params[0].equals(existingMethodParams[0])) {
          // nothing to be done to params; this method params will override
          // existing ones so just return it so that the existing one is removed
          // from DDL queue
          return params;
        }
        else {
          return NOT_MERGED;
        }
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return false;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_DBPROPERTY";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[0];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        sb.append("CALL SYSCS_UTIL.SET_DATABASE_PROPERTY('")
            .append((String)params[0]).append("',");
        if (params[1] != null) {
          sb.append('\'').append((String)params[1]).append('\'');
        }
        else {
          sb.append("NULL");
        }
        return sb.append(')').toString();
      }

      @Override
      void appendParams(Object[] params, StringBuilder sb) {
        // don't log private keys
        assert params.length == 2: params.length;

        String key = (String)params[0];
        String value = (String)params[1];
        if (key.startsWith(GfxdConstants.PASSWORD_PRIVATE_KEY_DB_PROP_PREFIX)) {
          value = "***";
        }
        sb.append("; key=").append(key).append(", value=").append(value);
      }
    },

    createUser {

      @Override
      boolean allowExecution(Object[] params) {
        // user changes will need to be done on all JVMs for authentication
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert String.class.isInstance(params[0])
            && (String.class.isInstance(params[1]) || params[1] == null);

        String key = (String)params[0];
        String value = (String)params[1];
        Misc.getMemStoreBooting().setProperty(key, value, false);

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "creating distributed system user (" + key + "). ");
          SanityManager.ASSERT(
              (PropertyUtil.whereSet(key, null) != PropertyUtil.SET_IN_JVM),
              "Cannot be a system user " + key);
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        int numPrms = InternalDataSerializer.readArrayLength(in);
        assert numPrms == 2;

        Object[] inParams = new Object[numPrms];
        for (int count = 0; count < numPrms; count++) {
          inParams[count] = InternalDataSerializer.readString(in);
        }

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        assert (params != null && params.length == 2);

        InternalDataSerializer.writeArrayLength(params.length, out);
        for (Object prm : params) {
          assert prm == null || String.class.isInstance(prm);
          InternalDataSerializer.writeString((String)prm, out);
        }
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return false;
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return false;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_USERS";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[0];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        return getCreateUserStatement((String)params[0], (String)params[1]);
      }
    },

    changePassword {

      @Override
      boolean allowExecution(Object[] params) {
        // user changes will need to be done on all JVMs for authentication
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert String.class.isInstance(params[0])
            && String.class.isInstance(params[1])
            && String.class.isInstance(params[2]);

        String key = (String)params[0];
        String value = (String)params[2];
        Misc.getMemStoreBooting().setProperty(key, value, false);

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "changing distributed system user password (" + key + "," + value + "). ");
          SanityManager.ASSERT(
              (PropertyUtil.whereSet(key, null) != PropertyUtil.SET_IN_JVM),
              "Cannot be a system user " + key);
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        int numPrms = InternalDataSerializer.readArrayLength(in);
        assert numPrms == 3 || numPrms == 4;

        Object[] inParams = new Object[numPrms];
        for (int count = 0; count < numPrms; count++) {
          inParams[count] = InternalDataSerializer.readString(in);
        }

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        assert (params != null && (params.length == 3 || params.length == 4));

        InternalDataSerializer.writeArrayLength(params.length, out);
        for (Object prm : params) {
          assert prm == null || String.class.isInstance(prm);
          InternalDataSerializer.writeString((String)prm, out);
        }
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return true;
      }

      @Override
      Object[] merge(Object[] params, SysProcMethod existingMethod,
          Object[] existingMethodParams) {
        if ((existingMethod == createUser || existingMethod == changePassword)
            && ArrayUtils.objectEquals(params[0], existingMethodParams[0])) {
          // new set of parameters to indicate conversion to createUser
          Object[] newParams = new Object[4];
          newParams[0] = params[0];
          newParams[1] = params[1];
          newParams[2] = params[2];
          newParams[3] = "converted to 'createUser'";
          return newParams;
        }
        else {
          return NOT_MERGED;
        }
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return false;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_USERS";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[0];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        if (params.length == 4) {
          return getCreateUserStatement((String)params[0], (String)params[2]);
        }
        else {
          final StringBuilder sb = new StringBuilder();
          sb.append("CALL SYS.CHANGE_PASSWORD('").append((String)params[0])
              .append("',");
          if (params[1] != null) {
            sb.append('\'').append((String)params[1]).append('\'');
          }
          else {
            sb.append("NULL");
          }
          if (params[2] != null) {
            sb.append('\'').append((String)params[2]).append('\'');
          }
          else {
            sb.append("NULL");
          }
          return sb.append(')').toString();
        }
      }
    },

    dropUser {

      @Override
      boolean allowExecution(Object[] params) {
        // user changes will need to be done on all JVMs for authentication
        return true;
      }

      @Override
      boolean initConnection() {
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert String.class.isInstance(params[0]) && params.length == 1;

        String userId = (String)params[0];
        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        Misc.getMemStoreBooting().setProperty(userId, null, false);

        // also drop all permissions for the user, if any
        String authId = userId;
        if (userId.startsWith(Property.USER_PROPERTY_PREFIX)) {
          authId = userId.substring(Property.USER_PROPERTY_PREFIX.length());
        } else if (userId.startsWith(Property.SQLF_USER_PROPERTY_PREFIX)) {
          authId = userId
              .substring(Property.SQLF_USER_PROPERTY_PREFIX.length());
        }
        lcc.getDataDictionary().dropAllPermsByGrantee(authId,
            lcc.getTransactionExecute());

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "dropped distributed system user (" + userId + ") ");
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        int numPrms = InternalDataSerializer.readArrayLength(in);
        assert numPrms == 1;
        Object[] inParams = new Object[numPrms];
        inParams[0] = InternalDataSerializer.readString(in);

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        assert (params != null && params.length == 1);

        InternalDataSerializer.writeArrayLength(1, out);
        assert String.class.isInstance(params[0]);
        InternalDataSerializer.writeString((String)params[0], out);
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return true;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_USERS";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[0];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.DROP_USER('").append((String)params[0])
            .append("')").toString();
      }
    },

    setCriticalHeapPercentage {

      @Override
      boolean allowExecution(Object[] params) throws StandardException {
        assert params[1] == null || params[1] instanceof String: params[1];
        if (super.allowExecution(params)) {
          // ignore if this node does not belong to the server groups
          return (params[1] == null || !CallbackProcedures
              .skipExecutionForGroupsAndTable((String)params[1], null));
        }
        else {
          return false;
        }
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert params[0] instanceof Float: params[0];

        float heapPercent = ((Float)params[0]).floatValue();
        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "about to invoke critical_heap_percentage (" + heapPercent
                  + ") for request originated remotely. ");
        }

        GfxdSystemProcedures.SET_CRITICAL_HEAP_PERCENTAGE(heapPercent);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] params = new Object[2];
        params[0] = InternalDataSerializer.readFloat(in);
        if ((flags & HAS_SERVER_GROUPS) != 0) {
          params[1] = InternalDataSerializer.readString(in);
        }
        return params;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {

        assert params != null && params.length == 2;

        InternalDataSerializer.writeFloat((Float)params[0], out);
        if (params[1] != null) {
          InternalDataSerializer.writeString((String)params[1], out);
        }
      }

      @Override
      short computeFlags(short flags, Object[] params) {
        if (params[1] != null) flags |= HAS_SERVER_GROUPS;
        return flags;
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return true;
      }

      @Override
      Object[] merge(Object[] params, SysProcMethod existingMethod,
          Object[] existingMethodParams) {
        if (existingMethod == setCriticalHeapPercentage
            && ArrayUtils.objectEquals(params[1], existingMethodParams[1])) {
          // nothing to be done to params; this method params will override
          // existing ones so just return it so that the existing one is removed
          // from DDL queue
          return params;
        }
        else {
          return NOT_MERGED;
        }
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return ((Float)params[0]).floatValue() == 0.0f;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_CRITICALHEAP";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[1];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        sb.append("CALL SYS.SET_CRITICAL_HEAP_PERCENTAGE_SG(")
            .append(params[0]).append(',');
        if (params[1] != null) {
          sb.append('\'').append((String)params[1]).append('\'');
        }
        else {
          sb.append("NULL");
        }
        return sb.append(')').toString();
      }
    },

    setCriticalOffHeapPercentage {

      @Override
      boolean allowExecution(Object[] params) throws StandardException {
        assert params[1] == null || params[1] instanceof String: params[1];
        if (super.allowExecution(params)) {
          // ignore if this node does not belong to the server groups
          return (params[1] == null || !CallbackProcedures
              .skipExecutionForGroupsAndTable((String)params[1], null));
        }
        else {
          return false;
        }
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert params[0] instanceof Float: params[0];

        float offHeapPercent = ((Float)params[0]).floatValue();
        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "about to invoke critical_off_heap_percentage (" + offHeapPercent
                  + ") for request originated remotely. ");
        }

        GfxdSystemProcedures.SET_CRITICAL_OFFHEAP_PERCENTAGE(offHeapPercent);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] params = new Object[2];
        params[0] = InternalDataSerializer.readFloat(in);
        if ((flags & HAS_SERVER_GROUPS) != 0) {
          params[1] = InternalDataSerializer.readString(in);
        }
        return params;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {

        assert params != null && params.length == 2;

        InternalDataSerializer.writeFloat((Float)params[0], out);
        if (params[1] != null) {
          InternalDataSerializer.writeString((String)params[1], out);
        }
      }

      @Override
      short computeFlags(short flags, Object[] params) {
        if (params[1] != null) flags |= HAS_SERVER_GROUPS;
        return flags;
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return true;
      }

      @Override
      Object[] merge(Object[] params, SysProcMethod existingMethod,
          Object[] existingMethodParams) {
        if (existingMethod == setCriticalOffHeapPercentage
            && ArrayUtils.objectEquals(params[1], existingMethodParams[1])) {
          // nothing to be done to params; this method params will override
          // existing ones so just return it so that the existing one is removed
          // from DDL queue
          return params;
        }
        else {
          return NOT_MERGED;
        }
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return ((Float)params[0]).floatValue() == 0.0f;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_CRITICALOFFHEAP";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[1];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        sb.append("CALL SYS.SET_CRITICAL_OFFHEAP_PERCENTAGE_SG(")
            .append(params[0]).append(',');
        if (params[1] != null) {
          sb.append('\'').append((String)params[1]).append('\'');
        }
        else {
          sb.append("NULL");
        }
        return sb.append(')').toString();
      }
      
      @Override
      public boolean isOffHeapMethod() {
        return true;
      }
    },
    
    setEvictionHeapPercentage {

      @Override
      boolean allowExecution(Object[] params) throws StandardException {
        assert params[1] == null || params[1] instanceof String: params[1];

        if (super.allowExecution(params)) {
          // ignore if this node does not belong to the server groups
          return (params[1] == null || !CallbackProcedures
              .skipExecutionForGroupsAndTable((String)params[1], null));
        }
        else {
          return false;
        }
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert params[0] instanceof Float: params[0];

        float heapPercent = ((Float)params[0]).floatValue();
        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "about to invoke eviction_heap_percentage (" + heapPercent
                  + ") for request originated remotely from " + sender);
        }

        GfxdSystemProcedures.SET_EVICTION_HEAP_PERCENTAGE(heapPercent);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {

        Object[] params = new Object[2];
        params[0] = InternalDataSerializer.readFloat(in);
        if ((flags & HAS_SERVER_GROUPS) != 0) {
          params[1] = InternalDataSerializer.readString(in);
        }

        return params;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {

        assert params != null && params.length == 2;

        InternalDataSerializer.writeFloat((Float)params[0], out);
        if (params[1] != null) {
          InternalDataSerializer.writeString((String)params[1], out);
        }
      }

      @Override
      short computeFlags(short flags, Object[] params) {
        if (params[1] != null) flags |= HAS_SERVER_GROUPS;
        return flags;
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return true;
      }

      @Override
      Object[] merge(Object[] params, SysProcMethod existingMethod,
          Object[] existingMethodParams) {
        if (existingMethod == setEvictionHeapPercentage
            && ArrayUtils.objectEquals(params[1], existingMethodParams[1])) {
          // nothing to be done to params; this method params will override
          // existing ones so just return it so that the existing one is removed
          // from DDL queue
          return params;
        }
        else {
          return NOT_MERGED;
        }
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return ((Float)params[0]).floatValue() == 0.0f;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_EVICTIONHEAP";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[1];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        sb.append("CALL SYS.SET_EVICTION_HEAP_PERCENTAGE_SG(")
            .append(params[0]).append(',');
        if (params[1] != null) {
          sb.append('\'').append((String)params[1]).append('\'');
        }
        else {
          sb.append("NULL");
        }
        return sb.append(')').toString();
      }
    },

    setEvictionOffHeapPercentage {

      @Override
      boolean allowExecution(Object[] params) throws StandardException {
        assert params[1] == null || params[1] instanceof String: params[1];

        if (super.allowExecution(params)) {
          // ignore if this node does not belong to the server groups
          return (params[1] == null || !CallbackProcedures
              .skipExecutionForGroupsAndTable((String)params[1], null));
        }
        else {
          return false;
        }
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        assert params[0] instanceof Float: params[0];

        float offHeapPercent = ((Float)params[0]).floatValue();
        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "about to invoke eviction_offheap_percentage (" + offHeapPercent
                  + ") for request originated remotely from " + sender);
        }

        GfxdSystemProcedures.SET_EVICTION_OFFHEAP_PERCENTAGE(offHeapPercent);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {

        Object[] params = new Object[2];
        params[0] = InternalDataSerializer.readFloat(in);
        if ((flags & HAS_SERVER_GROUPS) != 0) {
          params[1] = InternalDataSerializer.readString(in);
        }

        return params;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {

        assert params != null && params.length == 2;

        InternalDataSerializer.writeFloat((Float)params[0], out);
        if (params[1] != null) {
          InternalDataSerializer.writeString((String)params[1], out);
        }
      }

      @Override
      short computeFlags(short flags, Object[] params) {
        if (params[1] != null) flags |= HAS_SERVER_GROUPS;
        return flags;
      }

      @Override
      boolean shouldBeMerged(Object[] params) {
        return true;
      }

      @Override
      Object[] merge(Object[] params, SysProcMethod existingMethod,
          Object[] existingMethodParams) {
        if (existingMethod == setEvictionOffHeapPercentage
            && ArrayUtils.objectEquals(params[1], existingMethodParams[1])) {
          // nothing to be done to params; this method params will override
          // existing ones so just return it so that the existing one is removed
          // from DDL queue
          return params;
        }
        else {
          return NOT_MERGED;
        }
      }

      @Override
      boolean shouldBeConflated(Object[] params) {
        return ((Float)params[0]).floatValue() == 0.0f;
      }

      @Override
      String getRegionToConflate(Object[] params) {
        return "SYS.__GFXD_INTERNAL_EVICTIONOFFHEAP";
      }

      @Override
      Object getKeyToConflate(Object[] params) {
        return params[1];
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        sb.append("CALL SYS.SET_EVICTION_OFFHEAP_PERCENTAGE_SG(")
            .append(params[0]).append(',');
        if (params[1] != null) {
          sb.append('\'').append((String)params[1]).append('\'');
        }
        else {
          sb.append("NULL");
        }
        return sb.append(')').toString();
      }
      
      @Override
      public boolean isOffHeapMethod() {
        return true;
      }
    },
    
    setTraceFlag {

      @Override
      boolean allowExecution(Object[] params) {
        // some of the trace flags may need to be set on LOCATORs etc. also,
        // so allowing on all since it does not hurt in any case
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender) {

        String traceFlag = (String)params[0];
        Boolean on = (Boolean)params[1];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: setting DEBUG traceFlag '" + traceFlag
                + "' to " + on);
        if ("DistributionManager.VERBOSE".equals(traceFlag)) {
          DistributionManager.VERBOSE = on;
        }
        else if (on) {
          SanityManager.DEBUG_SET(traceFlag);
        }
        else {
          SanityManager.DEBUG_CLEAR(traceFlag);
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[2];
        inParams[0] = InternalDataSerializer.readString(in);
        inParams[1] = in.readBoolean();

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        InternalDataSerializer.writeString((String)params[0], out);
        out.writeBoolean((Boolean)params[1]);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.SET_TRACE_FLAG('").append(params[0])
            .append("',").append((Boolean)params[1] ? '1' : '0').append(')')
            .toString();
      }
    },

    waitForSenderQueueFlush {

      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isAccessorOrStore();
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender) {

        String id = (String)params[0];
        Boolean isAsyncListener = (Boolean)params[1];
        Integer maxWaitTime = (Integer)params[2];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: waiting for sender queue flush for id="
                + id + ", isAsyncListener=" + isAsyncListener
                + ", maxWaitTime=" + maxWaitTime);

        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache != null) {
          try {
            int elapsed = cache.waitForSenderQueueFlush(id, isAsyncListener,
                maxWaitTime);
            if (elapsed >= 0) {
              params[2] = Integer.valueOf(elapsed);
            }
          } catch (CancelException ce) {
            // ignore
          }
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[3];
        inParams[0] = InternalDataSerializer.readString(in);
        inParams[1] = in.readBoolean();
        inParams[2] = in.readInt();

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        InternalDataSerializer.writeString((String)params[0], out);
        out.writeBoolean((Boolean)params[1]);
        out.writeInt((Integer)params[2]);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.WAIT_FOR_ASYNC_QUEUE_FLUSH('")
            .append(params[0]).append("',")
            .append((Boolean)params[1] ? '1' : '0').append(',')
            .append(params[2]).append(')').toString();
      }
    },

    setLogLevel {

      @Override
      boolean allowExecution(Object[] params) {
        // so allowing log level to be set on all nodes including locator
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {
        try {
          String logClass = (String) params[0];
          Level level = ClientSharedUtils.convertToLog4LogLevel((String)params[1]);
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
                  "GfxdSystemProcedureMessage: setting log level for class '" + logClass
                          + "' to " + level);
          if (logClass.equals("")) {
            // sets the log level for the root logger and the GFXD bridge
            LogManager.getRootLogger().setLevel(level);
            LogWriter logger = Misc.getCacheLogWriterNoThrow();
            if (logger instanceof GFToSlf4jBridge) {
              ((GFToSlf4jBridge) logger).setLevelForLog4jLevel(level);
            }
          } else {
            LogManager.getLogger(logClass).setLevel(level);
          }
        } catch (Exception e) {
          throw StandardException.newException(
                  com.pivotal.gemfirexd.internal.iapi.reference.SQLState.GENERIC_PROC_EXCEPTION,
                  e, e.getMessage());
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[2];
        inParams[0] = InternalDataSerializer.readString(in);
        inParams[1] = InternalDataSerializer.readString(in);

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
              throws IOException {
        InternalDataSerializer.writeString((String)params[0], out);
        InternalDataSerializer.writeString((String)params[1], out);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.SET_LOG_LEVEL('").append(params[0])
                .append("', '").append(params[1]).append("')")
                .toString();
      }
    },

    setGatewayFKChecks {
      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isAccessorOrStore();
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender) {

        Boolean flag = (Boolean)params[0];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: Setting Gateway FK Checks to "
                + flag.booleanValue());

        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if(cache != null) {
          cache.setSkipFKChecksForGatewayEvents(!flag.booleanValue());
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[3];
        inParams[0] = InternalDataSerializer.readBoolean(in);
        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        InternalDataSerializer.writeBoolean((Boolean)params[0], out);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.SET_GATEWAY_FK_CHECKS(").append(params[0])
            .append(')').toString();
      }
          
    },

    incrementTableVersion {

      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isAccessorOrStore();
      }

      @Override
      boolean initConnection() {
        return true;
      }

      @Override
      boolean preprocess() {
        // should not be preprocessed in replay
        return false;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        String schemaName = (String)params[0];
        String tableName = (String)params[1];
        Integer increment = (Integer)params[2];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: incrementing table version for ["
                + schemaName + '.' + tableName + "] by " + increment);

        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        if (lcc == null) {
          throw StandardException.newException(SQLState.NO_CURRENT_CONNECTION);
        }
        GemFireContainer container = GemFireXDUtils.getGemFireContainer(
            schemaName, tableName, lcc);
        if (container == null) {
          throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND,
              Misc.getFullTableName(schemaName, tableName, lcc));
        }
        ExtraTableInfo tableInfo = container.getExtraTableInfo();
        container.schemaVersionChange(lcc.getDataDictionary(),
            tableInfo.getTableDescriptor(), lcc);
        container.getExtraTableInfo().refreshCachedInfo(null, container);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[3];
        inParams[0] = InternalDataSerializer.readString(in);
        inParams[1] = InternalDataSerializer.readString(in);
        inParams[2] = in.readInt();

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        InternalDataSerializer.writeString((String)params[0], out);
        InternalDataSerializer.writeString((String)params[1], out);
        out.writeInt((Integer)params[2]);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.INCREMENT_TABLE_VERSION('")
            .append(params[0]).append("','").append(params[1]).append("',")
            .append(params[2]).append(')').toString();
      }
    },

    diskStoreFsync {

      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isStore();
      }

      @Override
      boolean preprocess() {
        // should not be preprocessed in replay
        return false;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        String diskStoreName = (String)params[0];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: fsyncing diskstore " + diskStoreName);

        GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
        if (diskStoreName != null) {
          DiskStoreImpl diskStore = cache.findDiskStore(diskStoreName);
          if (diskStore == null) {
            // check for upper-case name
            diskStore = cache.findDiskStore(SharedUtils
                .SQLToUpperCase(diskStoreName));
            if (diskStore == null) {
              throw StandardException.newException(
                  SQLState.LANG_OBJECT_DOES_NOT_EXIST, "FSYNC DISKSTORE",
                  diskStoreName);
            }
          }
          diskStore.flushAndSync();
        }
        else {
          // fsync all disk stores
          for (DiskStoreImpl diskStore : cache
              .listDiskStoresIncludingRegionOwned()) {
            diskStore.flushAndSync();
          }
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[1];
        inParams[0] = InternalDataSerializer.readString(in);

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        InternalDataSerializer.writeString((String)params[0], out);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.DISKSTORE_FSYNC('").append(params[0])
            .append("')").toString();
      }
    },

    setStatementStats {

      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isAccessorOrStore();
      }

      @Override
      boolean initConnection() {
        return false;
      }

      @Override
      boolean preprocess() {
        // should not be preprocessed in replay
        return false;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        final Boolean enableStats = (Boolean)params[0];
        final Boolean enableTimeStats = (Boolean)params[1];

        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "GfxdSystemProcedure: Switching " + (enableStats ? "On" : "Off")
                + " statement statistics collection globally with timeStats="
                + enableTimeStats + '.');

        // first set the flags globally for all future connections
        PropertyUtil.setSystemProperty(GfxdConstants.GFXD_ENABLE_STATS,
            enableStats.toString());
        PropertyUtil.setSystemProperty(GfxdConstants.GFXD_ENABLE_TIMESTATS,
            enableTimeStats.toString());

        // next set on all active connections
        final GemFireXDUtils.Visitor<LanguageConnectionContext> setStats =
            new GemFireXDUtils.Visitor<LanguageConnectionContext>() {
              @Override
              public boolean visit(LanguageConnectionContext lcc) {
                lcc.setStatsEnabled(enableStats, enableTimeStats,
                    lcc.explainConnection());
                return true;
              }
            };
        GemFireXDUtils.forAllContexts(setStats);

        // also enable GemFire and other timing stats
        if (enableTimeStats) {
          DistributionStats.enableClockStats = true;
          CachePerfStats.enableClockStats = true;
          ConnectionStats.setClockStats(true, false);
        }
        else {
          // reset to default
          final boolean timeStatsEnabled = Misc.getDistributedSystem()
              .getConfig().getEnableTimeStatistics();
          DistributionStats.enableClockStats = timeStatsEnabled;
          CachePerfStats.enableClockStats = timeStatsEnabled;
          ConnectionStats.setClockStats(timeStatsEnabled, false);
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[3];
        inParams[0] = in.readBoolean();
        inParams[1] = in.readBoolean();

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        out.writeBoolean((Boolean)params[0]);
        out.writeBoolean((Boolean)params[1]);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.SET_GLOBAL_STATEMENT_STATISTICS(")
            .append(params[0] == Boolean.TRUE ? 1 : 0).append(',')
            .append(params[1] == Boolean.TRUE ? 1 : 0).append(')').toString();
      }
    },

    dumpStacks {

      @Override
      boolean allowExecution(Object[] params) {
        // allow dumping of stacks for all nodes
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender) {

        Object user = params[0];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: dumping thread stacks/locks "
                + "invoked by " + user);
        GemFireXDUtils.dumpStacks(Misc.getMemStoreBooting(),
            "SYS.DUMP_STACKS invoked by " + user);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException,
          ClassNotFoundException {
        return new Object[] { DataSerializer.readObject(in) };
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        DataSerializer.writeObject(params[0], out);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        return "CALL SYS.DUMP_STACKS(1)";
      }
    },

    forceHDFSWriteonlyFileRollover {

      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isStore();
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        String regionPath = Misc.getRegionPath((String)params[0]);
        Integer minSizeForFileRollover = (Integer)params[1];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage: waiting for file rollover for HDFS table "
                + params[0] +  ", minSizeForFileRollover=" + minSizeForFileRollover);

        try {
          HDFSRegionDirector.getInstance().
                closeWritersForRegion(regionPath, minSizeForFileRollover);
        } catch (IOException e) {
          throw StandardException.newException(SQLState.HDFS_ERROR, e, e.getMessage());
        }
        
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException {
        Object[] inParams = new Object[3];
        inParams[0] = InternalDataSerializer.readString(in);
        inParams[1] = in.readInt();
    
        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        InternalDataSerializer.writeString((String)params[0], out);
        out.writeInt((Integer)params[1]);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('")
            .append(params[0]).append("',")
          .append(params[1]).append(')').toString();
      }
    },

    setNanoTimerType {

      @Override
      boolean allowExecution(Object[] params) {
        // allow dumping of stacks for all nodes
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender) {

        Boolean useNativeTimer = (Boolean)params[0];
        String nativeTimerType = (String)params[1];
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage:SET_NANOTIMER_TYPE native timer: " + useNativeTimer + 
            " NativeTimerType: " + nativeTimerType );
        NanoTimer.setNativeTimer(useNativeTimer, nativeTimerType);
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException,
          ClassNotFoundException {
        Object[] inParams = new Object[2];
        inParams[0] = in.readBoolean();
        inParams[1] = in.readUTF();

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        out.writeBoolean((Boolean)params[0]);
        out.writeUTF((String)params[1]);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.SET_NANOTIMER_TYPE('").append(params[0])
            .append("','").append(params[1]).append("')").toString();
      }
    },

    checkTableEx {

      @Override
      boolean allowExecution(Object[] params) {
        return GemFireXDUtils.getMyVMKind().isStore();
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        if (lcc != null) {
          // do the actual work of checking indexes
          executeCheckTable(params);
        } else {
          // set up the lcc first
          EmbedConnection conn = null;
          StatementContext statementContext = null;
          boolean popContext = false;
          Throwable t = null;
          try {

            conn = GemFireXDUtils.getTSSConnection(true, true, true);
            conn.getTR().setupContextStack();

            synchronized (conn.getConnectionSynchronization()) {

              // create an artificial statementContext for simulating procedure call
              lcc = conn.getLanguageConnectionContext();
              lcc.pushMe();
              popContext = true;
              assert ContextService
                  .getContextOrNull(LanguageConnectionContext.CONTEXT_ID) != null;
              statementContext = lcc.pushStatementContext(false, false,
                  this.name(), null, false, 0L);
              statementContext
                  .setSQLAllowed(RoutineAliasInfo.MODIFIES_SQL_DATA, true);

              //now do the actual work of checking indexes
              executeCheckTable(params);
            }
          } finally {
            if (statementContext != null) {
              lcc.popStatementContext(statementContext, t);
            }
            if (lcc != null && popContext) {
              lcc.popMe();
            }
            if (conn != null) {
              conn.getTR().restoreContextStack();
            }
          }
          }
      }

      private void executeCheckTable(Object[] params) throws StandardException {
        try {
          String schema = (String)params[0];
          String table = (String)params[1];
          // member on which global index size are verified
          DistributedMember targetNode = (DistributedMember)params[2];

          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "GfxdSystemProcedureMessage:CHECK_TABLE_EX schema: " + schema +
                  " table: " + table + " target node to verify global " +
                  "index size:" + targetNode);

          // verify that local and global index contents are consistent
          SystemProcedures.CHECK_TABLE(schema, table);

          // instead of verifying global index region size on each node
          // just verify it on mentioned node as we need to verify
          // total region sizes for global index (and not local sizes)
          if (Misc.getMyId().equals(targetNode)) {
            verifyGlobalIndexSizes(params);
          }
        } catch (SQLException sq) {
          throw StandardException.unexpectedUserException(sq);
        }
      }

      // check whether the sizes of global index and base table are equal
      private void verifyGlobalIndexSizes(Object[] params) throws StandardException {
        String schema = (String)params[0];
        String table = (String)params[1];
        LocalRegion region = (LocalRegion)Misc.getRegionForTable(schema + "." + table, true);
        GfxdIndexManager indexManager = (GfxdIndexManager)region.getIndexUpdater();
        if (indexManager != null) {
          List<GemFireContainer> indexContainers = indexManager.getIndexContainers();
          if (indexContainers != null) {
            for (GemFireContainer indexContainer : indexContainers) {
              if (indexContainer.isGlobalIndex()) {
                int numTableEntries = region.size();
                int numIndexEntries = indexContainer.getRegion().size();
                if (numTableEntries != numIndexEntries) {
                  throw StandardException.newException(
                      SQLState.LANG_INDEX_ROW_COUNT_MISMATCH,
                      indexContainer.getName(), schema, table,
                      numIndexEntries, numTableEntries);
                }
              }
            }
          }
        }
      }

      @Override
      public Object[] readParams(DataInput in, short flags) throws IOException,
          ClassNotFoundException {
        Object[] inParams = new Object[3];
        inParams[0] = in.readUTF();
        inParams[1] = in.readUTF();
        inParams[2] = InternalDataSerializer.readObject(in);

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        out.writeUTF((String)params[0]);
        out.writeUTF((String)params[1]);
        InternalDataSerializer.writeObject(params[2], out);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("VALUES SYS.CHECK_TABLE_EX('").append(params[0])
            .append("','").append(params[1]).append("')").toString();
      }
    },

    refreshLdapGroup {

      @Override
      boolean allowExecution(Object[] params) {
        // only on process nodes
        return GemFireXDUtils.getMyVMKind().isAccessorOrStore();
      }

      @Override
      boolean initConnection() {
        return true;
      }

      @Override
      public void processMessage(Object[] params, DistributedMember sender)
          throws StandardException {

        String ldapGroup = (String)params[0];
        // current list of LDAP group members is passed to this method
        Set<?> currentMembers = (Set<?>)params[1];

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "refreshing LDAP group '" + ldapGroup + "' for all tables");
        }

        // scan the DataDictionary permissions tables to get all users of
        // the ldap group with permissions in tables or routines
        ArrayList<PermissionsDescriptor> groupDescs = new ArrayList<>();
        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        final TransactionController tc = lcc.getTransactionExecute();
        boolean disableLogging = !tc.needLogging();
        if (disableLogging) {
          tc.enableLogging();
        }
        try {
          final DataDictionary dd = lcc.getDataDictionary();
          dd.getAllLDAPDescriptorsHavingPermissions(ldapGroup,
              DataDictionary.SYSTABLEPERMS_CATALOG_NUM,
              SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, groupDescs);
          dd.getAllLDAPDescriptorsHavingPermissions(ldapGroup,
              DataDictionary.SYSROUTINEPERMS_CATALOG_NUM,
              SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, groupDescs);

          // collect the granted permissions for the LDAP group for new members
          // as well as collect all removed old LDAP members
          HashSet<String> removedMembers = new HashSet<>(groupDescs.size());
          HashSet<PermissionsDescriptor> groupPermissions = new HashSet<>();
          for (PermissionsDescriptor desc : groupDescs) {
            String grantee = desc.getGrantee();
            // collect the group descriptor for permissions to be granted
            // for new users
            if (grantee.startsWith(Constants.LDAP_GROUP_PREFIX)) {
              groupPermissions.add(desc);
            } else if (!currentMembers.contains(grantee)) {
              removedMembers.add(grantee);
            }
          }
          // collect the new members in the currentMembers list
          for (PermissionsDescriptor desc : groupDescs) {
            currentMembers.remove(desc.getGrantee());
          }

          // grant permission for new users for descriptors in groupPermissions
          // revoke permission for old users for descriptors in groupPermissions
          if (currentMembers.size() > 0 || removedMembers.size() > 0) {
            List<String> newMembers = null, oldMembers = null;
            if (currentMembers.size() > 0) {
              newMembers = new ArrayList<String>(currentMembers.size());
              for (Object m : currentMembers) {
                newMembers.add(ldapGroup + ':' + m);
              }
            }
            if (removedMembers.size() > 0) {
              oldMembers = new ArrayList<String>(removedMembers.size());
              for (Object m : removedMembers) {
                oldMembers.add(ldapGroup + ':' + m);
              }
            }
            ConstantActionActivation act = new ConstantActionActivation();
            act.initFromContext(lcc, false, null);
            for (PermissionsDescriptor perm : groupPermissions) {
              if (perm instanceof TablePermsDescriptor) {
                if (newMembers != null) {
                  grantRevokeGroupMember((TablePermsDescriptor)perm,
                      newMembers, ldapGroup, true, act, dd);
                }
                if (oldMembers != null) {
                  grantRevokeGroupMember((TablePermsDescriptor)perm,
                      oldMembers, ldapGroup, false, act, dd);
                }
              } else {
                RoutinePermsDescriptor routinePerm = (RoutinePermsDescriptor)perm;
                RoutinePrivilegeInfo privileges = new RoutinePrivilegeInfo(
                    dd.getAliasDescriptor(routinePerm.getRoutineUUID()));
                if (newMembers != null) {
                  privileges.executeGrantRevoke(act, true, newMembers);
                }
                if (oldMembers != null) {
                  privileges.executeGrantRevoke(act, false, oldMembers);
                }
              }
            }
          }
          // TODO: SW: need to take care of multiple LDAP groups so remove
          // only this one if user has for multiple groups
        } finally {
          tc.commit();
          // clear the plan cache on lead node
          GfxdDistributionAdvisor.GfxdProfile profile = GemFireXDUtils.getGfxdProfile(Misc.getMyId());
          if (profile != null && profile.hasSparkURL()) {
            CallbackFactoryProvider.getStoreCallbacks().clearSessionCache(true);
            CallbackFactoryProvider.getStoreCallbacks().refreshPolicies(ldapGroup);
          }
          if (disableLogging) {
            tc.disableLogging();
          }
        }
      }

      private void grantRevokeGroupMember(TablePermsDescriptor groupPerm,
          List<?> grantees, String ldapGroup, boolean grant,
          ConstantActionActivation activation, DataDictionary dd)
              throws StandardException {
        UUID tableUUID = groupPerm.getTableUUID();
        TableDescriptor td = dd.getTableDescriptor(tableUUID);
        String ldapGroupFull = Constants.LDAP_GROUP_PREFIX + ldapGroup;

        boolean[] actionAllowed = new boolean[TablePrivilegeInfo.ACTION_COUNT];
        FormatableBitSet[] columnBitSets =
            new FormatableBitSet[TablePrivilegeInfo.ACTION_COUNT];
        ColPermsDescriptor colDesc;

        // set allowed actions
        actionAllowed[TablePrivilegeInfo.SELECT_ACTION] = isAllowed(
            groupPerm.getSelectPriv());
        actionAllowed[TablePrivilegeInfo.INSERT_ACTION] = isAllowed(
            groupPerm.getInsertPriv());
        actionAllowed[TablePrivilegeInfo.UPDATE_ACTION] = isAllowed(
            groupPerm.getUpdatePriv());
        actionAllowed[TablePrivilegeInfo.DELETE_ACTION] = isAllowed(
            groupPerm.getDeletePriv());
        actionAllowed[TablePrivilegeInfo.REFERENCES_ACTION] = isAllowed(
            groupPerm.getReferencesPriv());
        actionAllowed[TablePrivilegeInfo.TRIGGER_ACTION] = isAllowed(
            groupPerm.getTriggerPriv());
        actionAllowed[TablePrivilegeInfo.ALTER_ACTION] = isAllowed(
            groupPerm.getAlterPriv());
        // get column permissions, if any, for LDAP group
        for (int[] actions : tableActions) {
          colDesc = dd.getColumnPermissions(tableUUID, actions[1], false,
              ldapGroupFull);
          if (colDesc != null) {
            columnBitSets[actions[0]] = colDesc.getColumns();
          }
        }

        TablePrivilegeInfo privileges = new TablePrivilegeInfo(td,
            actionAllowed, columnBitSets, null);
        privileges.executeGrantRevoke(activation, grant, grantees);
      }

      private boolean isAllowed(String privilege) {
        return "y".equalsIgnoreCase(privilege);
      }

      @Override
      public Object[] readParams(DataInput in, short flags)
          throws IOException, ClassNotFoundException {
        int numPrms = InternalDataSerializer.readArrayLength(in);
        assert numPrms == 2;

        Object[] inParams = new Object[numPrms];
        inParams[0] = InternalDataSerializer.readString(in);
        inParams[1] = InternalDataSerializer.readSet(in);

        return inParams;
      }

      @Override
      public void writeParams(Object[] params, DataOutput out)
          throws IOException {
        assert (params != null && params.length == 2);

        InternalDataSerializer.writeArrayLength(params.length, out);
        InternalDataSerializer.writeString((String)params[0], out);
        InternalDataSerializer.writeSet((Set<?>)params[1], out);
      }

      @Override
      String getSQLStatement(Object[] params) throws StandardException {
        final StringBuilder sb = new StringBuilder();
        return sb.append("CALL SYS.REFRESH_LDAP_GROUP('").append(params[0])
            .append("')").toString();
      }
    },
    ;

    static final int[][] tableActions = new int[][] {
        { TablePrivilegeInfo.SELECT_ACTION, Authorizer.SELECT_PRIV },
        { TablePrivilegeInfo.INSERT_ACTION, Authorizer.INSERT_PRIV },
        { TablePrivilegeInfo.UPDATE_ACTION, Authorizer.UPDATE_PRIV },
        { TablePrivilegeInfo.DELETE_ACTION, Authorizer.DELETE_PRIV },
        { TablePrivilegeInfo.REFERENCES_ACTION, Authorizer.REFERENCES_PRIV },
        { TablePrivilegeInfo.TRIGGER_ACTION, Authorizer.TRIGGER_PRIV },
        { TablePrivilegeInfo.ALTER_ACTION, Authorizer.ALTER_PRIV },
    };

    /**
     * Returns true is this procedure can be executed on this GemFireXD JVM even
     * if it is a {@link VMKind#LOCATOR}, for example.
     */
    boolean allowExecution(Object[] params) throws StandardException {
      return GemFireXDUtils.getMyVMKind().isAccessorOrStore();
    }

    public abstract void processMessage(Object[] params,
        DistributedMember sender) throws StandardException;

    abstract Object[] readParams(DataInput in, short flags) throws IOException,
        ClassNotFoundException;

    abstract void writeParams(Object[] params, DataOutput out)
        throws IOException;

    static String getCreateUserStatement(String user, String password)
        throws StandardException {
      final StringBuilder sb = new StringBuilder();
      sb.append("CALL SYS.CREATE_USER('").append(user).append("',");
      if (password != null) {
        sb.append('\'').append(password).append('\'');
      }
      else {
        sb.append("NULL");
      }
      return sb.append(')').toString();
    }

    short computeFlags(short flags, Object[] params) {
      return flags;
    }

    boolean initConnection() {
      return false;
    }
    
    public boolean isOffHeapMethod() {
      return false;
    }

    boolean shouldBeMerged(Object[] params) {
      return false;
    }

    /**
     * Token returned if {@link #merge(Object[], SysProcMethod, Object[])} was
     * unsuccessful for some reason.
     */
    static final Object[] NOT_MERGED = new Object[0];

    /**
     * Merge the existing system procedure into this. Returns {@link #NOT_MERGED}
     * if merge was unsuccessful else the new parameters for this procedure.
     */
    Object[] merge(Object[] params, SysProcMethod existingMethod,
        Object[] existingMethodParams) {
      throw new AssertionError("not expected to be invoked");
    }

    boolean shouldBeConflated(Object[] params) {
      return false;
    }

    String getRegionToConflate(Object[] params) {
      return null;
    }

    Object getKeyToConflate(Object[] params) {
      return null;
    }

    boolean preprocess() {
      return true;
    }

    abstract String getSQLStatement(Object[] params) throws StandardException;

    void appendParams(Object[] params, StringBuilder sb) {
      if (params != null) {
        sb.append("; params ");
        for (Object prm : params) {
          sb.append('[');
          sb.append(prm);
          sb.append("] ");
        }
      }
    }
  }

  private SysProcMethod procMethod;

  private Object[] params;
  
  private transient DistributedMember sender;

  private String currentSchemaName;

  private long connId;

  private long ddlId;

  private transient boolean initialDDLReplayInProgress;

  public GfxdSystemProcedureMessage() {
    this.currentSchemaName = null;
    this.connId = -1;
    this.ddlId = -1;
    this.procMethod = null;
    this.params = null;
    this.sender = null;
  }

  public GfxdSystemProcedureMessage(SysProcMethod procMethod, Object[] params,
      String currentSchemaName, long connId, long ddlId,
      DistributedMember sender) {
    this.currentSchemaName = currentSchemaName;
    this.connId = connId;
    this.ddlId = ddlId;
    this.procMethod = procMethod;
    this.params = params;
    this.sender = sender;
    this.initialDDLReplayInProgress = Misc.initialDDLReplayInProgress();
  }

  @Override
  protected void processMessage(DistributionManager dm) {

    this.sender = getSender();
    Throwable t = null;
    EmbedConnection conn = null;
    LanguageConnectionContext lcc = null;
    StatementContext statementContext = null;
    boolean popContext = false;
    try {
      if (!this.procMethod.allowExecution(this.params)) {
        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE,
              toString() + ": Skipping execution of system procedure on "
                  + GemFireXDUtils.getMyVMKind() + " JVM");
        }
        return;
      }

      // if operation does not require a connection, then just execute
      if (!this.procMethod.initConnection()) {

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "GfxdSystemProcedureMessage:processMessage: Processing "
                  + "GfxdSystemProcedureMessage with no connection " + this);
        }

        this.execute();
        return;
      }

      // setupContextManager
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage:processMessage: "
                + "About to acquire connection for " + this);
      }

      conn = GemFireXDUtils.getTSSConnection(true, true, true);
      conn.getTR().setupContextStack();

      synchronized (conn.getConnectionSynchronization()) {

        // create an artificial statementContext for simulating procedure call
        lcc = conn.getLanguageConnectionContext();
        lcc.pushMe();
        popContext = true;
        assert ContextService
            .getContextOrNull(LanguageConnectionContext.CONTEXT_ID) != null;
        statementContext = lcc.pushStatementContext(false, false,
            this.procMethod.name(), null, false, 0L);
        statementContext
            .setSQLAllowed(RoutineAliasInfo.MODIFIES_SQL_DATA, true);

        if (GemFireXDUtils.TraceSysProcedures) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
              "GfxdSystemProcedureMessage:processMessage: Processing "
                  + "GfxdSystemProcedureMessage " + this);
        }

        this.execute();
      }

    } catch (Exception ex) {
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
            "GfxdSystemProcedureMessage:processMessage: Exception occured "
                + "while processing message " + this, ex);
      }
      t = ex;
      throw new ReplyException("GfxdSystemProcedureMessage:processMessage: "
          + "Unexpected Exception on member " + dm.getDistributionManagerId(),
          ex);
    } finally {
      if (statementContext != null) {
        lcc.popStatementContext(statementContext, t);
      }
      if (lcc != null && popContext) {
        lcc.popMe();
      }
      if (conn != null) {
        conn.getTR().restoreContextStack();
      }
    }
  }

  /* this getting called within processMessage & have proper
   * context manager set, so no worries. 
   */
  @Override
  public void execute() throws StandardException {

    if (!this.procMethod.allowExecution(this.params)) {
      if (GemFireXDUtils.TraceSysProcedures) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE,
            toString() + ": Skipping execution of system procedure on "
            + GemFireXDUtils.getMyVMKind() + " JVM");
      }
      return;
    }

    if (GemFireXDUtils.TraceSysProcedures) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SYS_PROCEDURES,
          "GfxdSystemProcedureMessage:execute: " + " calling "
              + this.procMethod);
    }

    this.procMethod.processMessage(this.params, sender);
  }

  @Override
  public byte getGfxdID() {
    return GfxdSerializable.GFXD_SYSTEM_PROCEDURE_MSG;
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.currentSchemaName = InternalDataSerializer.readString(in);
    this.connId = in.readLong();
    this.ddlId = in.readLong();
    int ordinal = in.readByte();
    this.procMethod = SysProcMethod.values()[ordinal];
    this.params = this.procMethod.readParams(in, flags);
    this.initialDDLReplayInProgress =
        (flags & INITIAL_DDL_REPLAY_IN_PROGRESS) != 0;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    super.toData(out);
    InternalDataSerializer.writeString(this.currentSchemaName, out);
    out.writeLong(this.connId);
    out.writeLong(this.ddlId);
    out.writeByte(this.procMethod.ordinal());
    this.procMethod.writeParams(this.params, out);
  }

  @Override
  protected short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    // if this node has initial DDL replay in progress then don't wait for node
    // initialization on remote node else it may result in a deadlock
    if (initialDDLReplayInProgress) flags |= INITIAL_DDL_REPLAY_IN_PROGRESS;
    return this.procMethod.computeFlags(flags, this.params);
  }

  @Override
  protected boolean waitForNodeInitialization() {
    return !this.initialDDLReplayInProgress;
  }

  @Override
  public void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append("; procedureMethod='").append(this.procMethod.toString())
        .append('\'');
    if (this.initialDDLReplayInProgress) {
      sb.append("; initialDDLReplayInProgress=true");
    }
    this.procMethod.appendParams(this.params, sb);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean shouldBeMerged() {
    return this.procMethod.shouldBeMerged(this.params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean merge(Conflatable existing) {
    if (existing instanceof GfxdSystemProcedureMessage) {
      GfxdSystemProcedureMessage msg = (GfxdSystemProcedureMessage)existing;
      final Object[] newParams = this.procMethod.merge(this.params,
          msg.procMethod, msg.params);
      if (newParams != SysProcMethod.NOT_MERGED) {
        this.params = newParams;
        return true;
      }
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean shouldBeConflated() {
    return this.procMethod.shouldBeConflated(this.params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getRegionToConflate() {
    return this.procMethod.getRegionToConflate(this.params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getKeyToConflate() {
    return this.procMethod.getKeyToConflate(this.params);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValueToConflate() {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean preprocess() {
    return this.procMethod.preprocess();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSQLStatement() throws StandardException {
    return this.procMethod.getSQLStatement(this.params);
  }

  public SysProcMethod getSysProcMethod() {
    return this.procMethod;
  }

  public Object[] getParameters() {
    return this.params;
  }
}
