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
package com.pivotal.gemfirexd.internal.impl.sql.catalog;

import java.sql.Types;

import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.CatalogRowFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.GfxdGatewayReceiverDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SystemColumn;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TupleDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;

public class GfxdSysGatewayReceiverRowFactory extends CatalogRowFactory {

  public static final String TABLENAME_STRING = "GATEWAYRECEIVERS";

  public static final int GATEWAYRECEIVER_COLUMN_COUNT = 9;

  public static final int ID = 1;

  public static final int RUNNING_PORT = 2;

  public static final int START_PORT = 3;

  public static final int END_PORT = 4;

  public static final int SERVER_GROUPS = 5;

  public static final int SOCKET_BUFFER_SIZE = 6;

  public static final int MAX_TIME_BETWEEN_PINGS = 7;
  
  public static final int BIND_ADDRESS = 8;
  
  public static final int HOST_NAME_FOR_SENDERS = 9;

  private static final int[][] indexColumnPositions = { { ID } };

  private static final boolean[] uniqueness = null;

  private static final String[] uuids = {
      "r073400e-00b6-fdfc-71ce-000b0a763800",
      "r073400e-00b6-fbba-75d4-000b0a763800",
      "r073400e-00b6-00b9-bbde-000b0a763800" };

  GfxdSysGatewayReceiverRowFactory(UUIDFactory uuidf, ExecutionFactory ef,
      DataValueFactory dvf) {
    super(uuidf, ef, dvf);
    initInfo(GATEWAYRECEIVER_COLUMN_COUNT, TABLENAME_STRING,
        indexColumnPositions, uniqueness, uuids);
  }

  public ExecRow makeRow(TupleDescriptor td, TupleDescriptor parent)
      throws StandardException {
    DataTypeDescriptor dtd;
    ExecRow row;
    DataValueDescriptor col;
    UUID uuid = null;
    String id = null;
    String serverGroup = null;
    Integer startPort = null;
    Integer endPort = null;
    Integer runningPort = null;
    Integer maxTimeBetPings = null;
    Integer socketBufferSize = null;
    String bindAdd = null;
    String hostNameForSenders = null;

    if (td != null) {
      GfxdGatewayReceiverDescriptor dsd = (GfxdGatewayReceiverDescriptor)td;
      uuid = dsd.getUUID();
      id = dsd.getId();
      runningPort = dsd.getRunningPort();
      startPort = dsd.getStartPort();
      endPort = dsd.getEndPort();
      serverGroup = dsd.getServerGroup();
      socketBufferSize = dsd.getSocketBufferSize();
      maxTimeBetPings = dsd.getMaxTimeBetweenPings();
      bindAdd= dsd.getBindAddress();
      hostNameForSenders= dsd.getHostNameForSenders();
    }

    /* Build the row to insert */
    row = getExecutionFactory().getValueRow(GATEWAYRECEIVER_COLUMN_COUNT);
    row.setColumn(ID, new SQLChar(id));
    row.setColumn(RUNNING_PORT, new SQLInteger(runningPort));
    row.setColumn(START_PORT, new SQLInteger(startPort));
    row.setColumn(END_PORT, new SQLInteger(endPort));
    row.setColumn(SERVER_GROUPS, new SQLChar(serverGroup));
    row.setColumn(SOCKET_BUFFER_SIZE, new SQLInteger(socketBufferSize));
    row.setColumn(MAX_TIME_BETWEEN_PINGS, new SQLInteger(maxTimeBetPings));
    row.setColumn(BIND_ADDRESS, new SQLChar(bindAdd));
    row.setColumn(HOST_NAME_FOR_SENDERS, new SQLChar(hostNameForSenders));
    return row;
  }

  public TupleDescriptor buildDescriptor(ExecRow row,
      TupleDescriptor parentDesc, DataDictionary dd) throws StandardException

  {
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(row.nColumns() == GATEWAYRECEIVER_COLUMN_COUNT,
          "Wrong number of columns for a GATEWAYRECEIVERS row");
    }

    DataValueDescriptor col;
    UUID uuid;

    col = row.getColumn(ID);
    String id = col.getString();

    uuid = getUUIDFactory().recreateUUID(id);

    col = row.getColumn(RUNNING_PORT);
    Integer runningPort = col.getInt();

    col = row.getColumn(START_PORT);
    Integer startPort = col.getInt();

    col = row.getColumn(END_PORT);
    Integer endPort = col.getInt();

    col = row.getColumn(SERVER_GROUPS);
    String serverGroups = col.getString();

    col = row.getColumn(SOCKET_BUFFER_SIZE);
    Integer socketBufferSize = col.getInt();

    col = row.getColumn(MAX_TIME_BETWEEN_PINGS);
    Integer maxTimeBetweenPings = col.getInt();
    
    col = row.getColumn(BIND_ADDRESS);
    String bindAdd = col.getString();
    
    col = row.getColumn(HOST_NAME_FOR_SENDERS);
    String hostNameForSenders = col.getString();

    return new GfxdGatewayReceiverDescriptor(dd, uuid, id, serverGroups,
        startPort, endPort, runningPort, socketBufferSize, maxTimeBetweenPings, bindAdd, hostNameForSenders);
  }

  public SystemColumn[] buildColumnList() {
    return new SystemColumn[] {
        SystemColumnImpl.getIdentifierColumn("ID", false),
        SystemColumnImpl.getColumn("RUNNING_PORT", Types.INTEGER, false),
        SystemColumnImpl.getColumn("START_PORT", Types.INTEGER, false),
        SystemColumnImpl.getColumn("END_PORT", Types.INTEGER, false),
        SystemColumnImpl.getColumn("SERVER_GROUPS", Types.VARCHAR, false),
        SystemColumnImpl.getColumn("SOCKET_BUFFER_SIZE", Types.INTEGER, false),
        SystemColumnImpl.getColumn("MAX_TIME_BETWEEN_PINGS", Types.INTEGER, false),
        SystemColumnImpl.getColumn("BIND_ADDRESS", Types.VARCHAR, false),
        SystemColumnImpl.getColumn("HOST_NAME_FOR_SENDERS", Types.VARCHAR, false) };
  }

}
