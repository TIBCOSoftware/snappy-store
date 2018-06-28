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

package com.pivotal.gemfirexd.internal.engine.diag;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.Role;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.execute.InternalResultSender;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.UpdateVTITemplate;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdConfigMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.execute.UpdatableResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.sql.GenericColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.vti.VTIEnvironment;

/**
 * A virtual table that shows all the {@link DistributedMember}s currently in
 * the distributed system. It also allows for updating of a few columns like
 * ROLES, SERVERGROUPS, KIND that will lead to change in the respective VM
 * properties.
 * <p>
 * This virtual table can be invoked by calling it directly:
 * 
 * <PRE>
 * select * from SYS.MEMBERS
 * </PRE>
 * <p>
 * The update to the updatable columns currently works by using an updateable
 * Statement/PreparedStatement e.g. using "createStatement(
 * {@link ResultSet#TYPE_FORWARD_ONLY}, {@link ResultSet#CONCUR_UPDATABLE})" to
 * query and update rows on the fly. An explicit update DML does not work yet.
 * 
 * @author swale
 */
public final class DistributedMembers extends UpdateVTITemplate {

  /** distribution manager for this VM */
  private final DM dm;

  /** iterator over all the members in the distributed system */
  private Iterator<InternalDistributedMember> memberIter;

  /**
   * Map of distributed member to its properties including system properties,
   * GFE properties and GemFireXD specific properties. This is obtained by sending
   * a message to all members when properties column is accessed.
   */
  private volatile Map<DistributedMember, Properties[]> memberPropsMap;

  /** the current member being iterated by the ResultSet */
  private InternalDistributedMember currentMember;

  /**
   * Token to indicate that the value of a column is not available. It happens
   * when a properties column is accessed for pure GFE members since GemFireXD
   * messages cannot be processed by those.
   */
  private static final String NOT_AVAIL_TOKEN = "NOT AVAILABLE";

  private Activation activation;

  public DistributedMembers() {
    this.dm = Misc.getDistributedSystem().getDistributionManager();
  }

  @SuppressWarnings("unchecked")
  public boolean next() throws SQLException {
    try {
      if (this.memberIter == null) {
        /* [sb] commenting for #43219 whereby now local data 
         * only is returned as VTI involving queries are
         * sprayed to every member.
        Set<InternalDistributedMember> allMembers = this.dm
            .getDistributionManagerIdsIncludingAdmin();
        // add self if not present
        final InternalDistributedMember myId = this.dm
            .getDistributionManagerId();
        if (!allMembers.contains(myId)) {
          allMembers = new HashSet<InternalDistributedMember>(allMembers);
          allMembers.add(myId);
        }
        */
        final Set<InternalDistributedMember> allMembers =
          new HashSet<InternalDistributedMember>();
        if (this.activation != null
            && this.activation.getFunctionContext() != null
            && ((InternalResultSender)this.activation.getFunctionContext()
                .getResultSender()).isLocallyExecuted()) {
          final Set<InternalDistributedMember> allDMs = this.dm
              .getDistributionManagerIdsIncludingAdmin();
          final Set<InternalDistributedMember> nonAdminDMs = this.dm
              .getDistributionManagerIds();
          allMembers.addAll(allDMs);
          allMembers.removeAll(nonAdminDMs);
          allMembers.add(Misc.getMyId());
        }
        else {
          allMembers.add(Misc.getMyId());
        }
        this.memberIter = allMembers.iterator();
      }
      if (this.memberIter.hasNext()) {
        this.currentMember = this.memberIter.next();
        this.wasNull = false;
        if (GemFireXDUtils.TraceMembers) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_MEMBERS,
              "DistributedMembers#next: read new member ["
                  + this.currentMember + "] with profile: "
                  + GemFireXDUtils.getGfxdProfile(this.currentMember));
        }
        return true;
      }
    } catch (Throwable t) {
      throw Util.javaException(t);
    }
    this.activation = null;
    return false;
  }

  @Override
  public boolean getBoolean(int columnNumber) {
    ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    if (desc.getType().getJDBCTypeId() != Types.BOOLEAN) {
      dataTypeConversion("boolean", desc);
    }
    this.wasNull = false;
    final String columnName = desc.getName();
    if (HOSTDATA.equals(columnName)) {
      final VMKind vmKind = GemFireXDUtils.getVMKind(this.currentMember);
      if (vmKind != null) {
        return (vmKind == VMKind.DATASTORE);
      }
      else {
        this.wasNull = true;
        return false;
      }
    }
    else if (ISELDER.equals(columnName)) {
      return this.currentMember.equals(this.dm.getElderId());
    }
    throw new GemFireXDRuntimeException("unexpected columnName " + columnName);
  }

  @Override
  public int getInt(int columnNumber) {
    ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    if (desc.getType().getJDBCTypeId() != Types.INTEGER) {
      dataTypeConversion("integer", desc);
    }
    this.wasNull = false;
    final String columnName = desc.getName();
    if (PID.equals(columnName)) {
      return this.currentMember.getProcessId();
    }
    else if (PORT.equals(columnName)) {
      return this.currentMember.getPort();
    }
    throw new GemFireXDRuntimeException("unexpected columnName " + columnName);
  }

  @Override
  public Object getObjectForColumn(int columnNumber) throws SQLException {
    final ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    final String columnName = desc.getName();
    final Object res;
    if (MEMBERID.equals(columnName)) {
      res = this.currentMember.getId();
    }
    else if (VMKIND.equals(columnName)) {
      final VMKind vmKind = GemFireXDUtils.getVMKind(this.currentMember);
      final StringBuilder sb = new StringBuilder();
      if (vmKind != null) {
        sb.append(vmKind.toString()).append('(');
      }
      switch (this.currentMember.getVmKind()) {
        case DistributionManager.NORMAL_DM_TYPE:
        case DistributionManager.LOCATOR_DM_TYPE:
          sb.append("normal");
          break;
        case DistributionManager.ADMIN_ONLY_DM_TYPE:
          sb.append("admin");
          break;
        case DistributionManager.LONER_DM_TYPE:
          sb.append("loner");
          break;
        default:
          sb.append("unknown[" + currentMember.getVmKind() + ']');
          break;
      }
      if (vmKind != null) {
        sb.append(')');
      }
      res = sb.toString();
    }
    else if (STATUS.equals(columnName)) {
      final FabricService service = FabricServiceManager.currentFabricServiceInstance();
      if(service != null) {
        res = service.serviceStatus().name();
      }
      else {
        res = "UNKNOWN";
      }
    }
    else if (HOSTDATA.equals(columnName)) {
      final VMKind vmKind = GemFireXDUtils.getVMKind(this.currentMember);
      if (vmKind != null) {
        res = Boolean.valueOf(vmKind == VMKind.DATASTORE);
      }
      else {
        res = null;
      }
    }
    else if (ISELDER.equals(columnName)) {
      res = Boolean.valueOf(this.currentMember.equals(this.dm.getElderId()));
    }
    else if (IPADDRESS.equals(columnName)) {
      res = this.currentMember.getIpAddress().toString();
    }
    else if (HOST.equals(columnName)) {
      res = this.currentMember.getHost();
    }
    else if (PID.equals(columnName)) {
      res = Integer.valueOf(this.currentMember.getProcessId());
    }
    else if (PORT.equals(columnName)) {
      res = Integer.valueOf(this.currentMember.getPort());
    }
    else if (ROLES.equals(columnName)) {
      final SortedSet<Role> sortedRoles = new TreeSet<Role>(
          this.currentMember.getRoles());
      res = SharedUtils.toCSV(sortedRoles);
    }
    else if (NETSERVERS.equals(columnName)) {
      GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
      String thriftServers = advisor.getThriftServers(this.currentMember);
      if (thriftServers.isEmpty()) {
        res = advisor.getDRDAServers(this.currentMember);
      } else {
        String drdaServers = advisor.getDRDAServers(this.currentMember);
        if (drdaServers.isEmpty()) {
          res = thriftServers;
        } else {
          res = thriftServers + ',' + drdaServers;
        }
      }
    }
    else if (THRIFTSERVERS.equals(columnName)) {
      res = GemFireXDUtils.getGfxdAdvisor()
          .getThriftServers(this.currentMember);
    }
    else if (LOCATOR.equals(columnName)) {
      res = GemFireXDUtils.getGfxdAdvisor().getServerLocator(this.currentMember);
    }
    else if (MANAGERINFO.equals(columnName)) {
     res = GemFireXDUtils.getManagerInfo(this.currentMember);
    }
    else if (SERVERGROUPS.equals(columnName)) {
      final SortedSet<String> groups = ServerGroupUtils
          .getServerGroups(this.currentMember);
      res = SharedUtils.toCSV(groups);
    }
    else if (SYSTEMPROPS.equals(columnName)) {
      final Properties[] memberRes = getMemberPropsMap()
          .get(this.currentMember);
      if (memberRes != null) {
        StringPrintWriter pw = new StringPrintWriter();
        pw.println();
        res = GemFireXDUtils.dumpProperties(
            memberRes[GfxdConfigMessage.SYSPROPS_INDEX], "System Properties",
            "", true, pw).toString();
      }
      else {
        res = NOT_AVAIL_TOKEN;
      }
    }
    else if (GFEPROPS.equals(columnName)) {
      final Properties[] memberRes = getMemberPropsMap()
          .get(this.currentMember);
      if (memberRes != null) {
        StringPrintWriter pw = new StringPrintWriter();
        pw.println();
        res = GemFireXDUtils.dumpProperties(
            memberRes[GfxdConfigMessage.GFEPROPS_INDEX], "GemFire Properties",
            "", true, pw).toString();
      }
      else {
        res = NOT_AVAIL_TOKEN;
      }
    }
    else if (GFXDPROPS.equals(columnName)) {
      final Properties[] memberRes = getMemberPropsMap()
          .get(this.currentMember);
      if (memberRes != null) {
        StringPrintWriter pw = new StringPrintWriter();
        pw.println();
        res = GemFireXDUtils.dumpProperties(
            memberRes[GfxdConfigMessage.GFXDPROPS_INDEX], "GemFireXD Properties",
            "", true, pw).toString();
      }
      else {
        res = "NOT GEMFIREXD";
      }
    }
    else {
      throw new GemFireXDRuntimeException("unexpected columnName " + columnName);
    }
    return res;
  }

  @Override
  public Activation getActivation() {
    return this.activation;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setActivation(final Activation act) {
    this.activation = act;
  }

  @Override
  public void updateRow(ExecRow row) throws StandardException {
    // sanity check for update only to GFXD VMs
    final VMKind vmKind = GemFireXDUtils.getVMKind(this.currentMember);
    if (vmKind == null) {
      throw StandardException.newException(
          SQLState.CURSOR_COLUMN_NOT_UPDATABLE, new GemFireXDRuntimeException(
              "Member with ID [" + this.currentMember.getId()
                  + "] is not yet part of GemFireXD"));
    }
    // extract the column positions that have been updated applying any
    // projections; also get the columns values since the order in ExecRow
    // is the same as that of updated columns list
    final BaseActivation activation = (BaseActivation)this.activation;
    boolean[] updatedColumns = activation.getUpdatedColumns();
    int[] projectMapping = activation.getProjectMapping();
    ArrayList<Integer> updatedColumnIndex = new ArrayList<Integer>();
    ArrayList<DataValueDescriptor> updatedDVDs =
      new ArrayList<DataValueDescriptor>();
    for (int colIndex = 0; colIndex < updatedColumns.length; colIndex++) {
      if (updatedColumns[colIndex]) {
        if (projectMapping != null) {
          updatedColumnIndex.add(projectMapping[colIndex] - 1);
          updatedDVDs.add(row.getColumn(colIndex + 1));
        }
        else {
          updatedColumnIndex.add(colIndex);
          updatedDVDs.add(row.getColumn(colIndex + 1));
        }
      }
    }
    if (GemFireXDUtils.TraceMembers) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_MEMBERS,
          "DistributedMembers#updateRow: updating member ["
              + this.currentMember + "] having profile ["
              + GemFireXDUtils.getGfxdProfile(this.currentMember)
              + " with new row: " + row);
    }
    for (int index = 0; index < updatedColumnIndex.size(); ++index) {
      final int columnIndex = updatedColumnIndex.get(index);
      // allow update of server groups and client/server GFXD role
      final ResultColumnDescriptor desc = columnInfo[columnIndex];
      final String columnName = desc.getName();
      try {
        final GfxdConfigMessage.Operation op;
        if (HOSTDATA.equals(columnName)) {
          // don't allow update of "host-data" for locators
          if (vmKind == VMKind.LOCATOR) {
            throw StandardException.newException(
                SQLState.CURSOR_COLUMN_NOT_UPDATABLE,
                new GemFireXDRuntimeException("Cannot update \"" + HOSTDATA
                    + "\" column in GemFireXD locator with ID ["
                    + this.currentMember.getId() + ']'));
          }
          op = GfxdConfigMessage.Operation.SET_CLIENT_OR_SERVER;
        }
        else if (SERVERGROUPS.equals(columnName)) {
          // don't allow update of "host-data" for locators
          if (vmKind == VMKind.LOCATOR) {
            throw StandardException.newException(
                SQLState.CURSOR_COLUMN_NOT_UPDATABLE,
                new GemFireXDRuntimeException("Cannot update \"" + SERVERGROUPS
                    + "\" column in GemFireXD locator with ID ["
                    + this.currentMember.getId() + ']'));
          }
          op = GfxdConfigMessage.Operation.SET_SERVERGROUPS;
        }
        else {
          String cursorName = activation.getCursorName();
          if (cursorName == null) {
            activation.setCursorName(activation.getLanguageConnectionContext()
                .getUniqueCursorName());
            cursorName = activation.getCursorName();
          }
          throw StandardException.newException(
              SQLState.LANG_COLUMN_NOT_UPDATABLE_IN_CURSOR, columnName,
              cursorName);
        }
        final DataValueDescriptor newDVD = updatedDVDs.get(index);
        final Object newVal = (newDVD.isNull() ? null : newDVD.getObject());
        final GfxdListResultCollector collector = new GfxdListResultCollector();
        final GfxdConfigMessage<Object> msg =
          new GfxdConfigMessage<Object>(collector,
              Collections.<DistributedMember> singleton(this.currentMember),
              op, newVal, false);
        // wait for update to complete though nothing to be done of the result
        msg.executeFunction();
      } catch (Throwable t) {
        throw StandardException.unexpectedUserException(t);
      }
    }
  }

  @Override
  public void close() throws SQLException {
    super.close();
    this.memberIter = null;
    this.memberPropsMap = null;
    this.currentMember = null;
    this.activation = null;
  }

  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /**
   * @see UpdatableResultSet#isForUpdate()
   */
  @Override
  public boolean isForUpdate() {
    return true;
  }

  @Override
  public double getEstimatedRowCount(VTIEnvironment vtiEnvironment)
      throws SQLException {
    return this.dm.getDistributionManagerIdsIncludingAdmin().size();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void setColumnDescriptorList(TableDescriptor td) {
    final ColumnDescriptorList cdl = td.getColumnDescriptorList();
    for (int index = 0; index < columnInfo.length; ++index) {
      final ResultColumnDescriptor rcd = columnInfo[index];
      final ColumnDescriptor cd = new ColumnDescriptor(rcd.getName(),
          index + 1, rcd.getType(), null, null, td.getUUID(), null, 0L, 0L, 0L,false);
      cdl.add(cd);
    }
  }

  private Map<DistributedMember, Properties[]> getMemberPropsMap()
      throws SQLException {
    if (this.memberPropsMap == null) {
      try {
        final MemberSingleResultCollector<Properties[]> collector =
          new MemberSingleResultCollector<Properties[]>();
        final DistributedMember myId = this.dm.getDistributionManagerId();
        /* [sb] restricting to self for fixing #43219
         final GfxdConfigMessage<TreeMap<DistributedMember, TreeMap<?, ?>[]>> msg
          = new GfxdConfigMessage<TreeMap<DistributedMember, TreeMap<?, ?>[]>>(
              collector, null, GfxdConfigMessage.Operation.GET_ALLPROPS,
              null, true); 
         */
        final GfxdConfigMessage<TreeMap<DistributedMember, Properties[]>> msg =
          new GfxdConfigMessage<TreeMap<DistributedMember, Properties[]>>(
            collector, Collections.singleton(myId),
            GfxdConfigMessage.Operation.GET_ALLPROPS, null, true);
        this.memberPropsMap = msg.executeFunction();
      } catch (Throwable t) {
        throw Util.javaException(t);
      }
    }
    return this.memberPropsMap;
  }

  /** Metadata */

  public static final String MEMBERID = "ID";

  public static final String VMKIND = "KIND";

  public static final String STATUS = "STATUS";

  public static final String HOSTDATA = "HOSTDATA";

  public static final String ISELDER = "ISELDER";

  public static final String IPADDRESS = "IPADDRESS";

  public static final String HOST = "HOST";

  public static final String PID = "PID";

  public static final String PORT = "PORT";

  public static final String ROLES = "ROLES";

  public static final String NETSERVERS = "NETSERVERS";

  public static final String THRIFTSERVERS = "THRIFTSERVERS";

  public static final String LOCATOR = "LOCATOR";

  public static final String SERVERGROUPS = "SERVERGROUPS";

  public static final String SYSTEMPROPS = "SYSTEMPROPS";

  public static final String GFEPROPS = "GEMFIREPROPS";

  public static final String GFXDPROPS = "BOOTPROPS";

  public static final String MANAGERINFO = "MANAGERINFO";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMBERID, Types.VARCHAR,
          false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(VMKIND, Types.VARCHAR,
          false, 24),
      EmbedResultSetMetaData.getResultColumnDescriptor(STATUS, Types.VARCHAR,
          false, 24),
      new GenericColumnDescriptor(HOSTDATA,
          SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME,
          GfxdDataDictionary.DIAG_MEMBERS_TABLENAME, -1,
          DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BOOLEAN, true),
          true, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(ISELDER, Types.BOOLEAN,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(IPADDRESS,
          Types.VARCHAR, true, 64),
      EmbedResultSetMetaData.getResultColumnDescriptor(HOST, Types.VARCHAR,
          true, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(PID, Types.INTEGER,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(PORT, Types.INTEGER,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(ROLES, Types.VARCHAR,
          false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(NETSERVERS,
          Types.VARCHAR, false, TypeId.VARCHAR_MAXWIDTH),
      EmbedResultSetMetaData.getResultColumnDescriptor(THRIFTSERVERS,
          Types.VARCHAR, false, TypeId.VARCHAR_MAXWIDTH),
      EmbedResultSetMetaData.getResultColumnDescriptor(LOCATOR,
          Types.VARCHAR, true, TypeId.VARCHAR_MAXWIDTH),
      new GenericColumnDescriptor(SERVERGROUPS,
          SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME,
          GfxdDataDictionary.DIAG_MEMBERS_TABLENAME, -1,
          DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, false,
              TypeId.VARCHAR_MAXWIDTH), true, false),

      EmbedResultSetMetaData.getResultColumnDescriptor(MANAGERINFO,
          Types.VARCHAR, false, 128),

      EmbedResultSetMetaData.getResultColumnDescriptor(SYSTEMPROPS, Types.CLOB,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(GFEPROPS, Types.CLOB,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(GFXDPROPS, Types.CLOB,
          false), };  

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);
}
