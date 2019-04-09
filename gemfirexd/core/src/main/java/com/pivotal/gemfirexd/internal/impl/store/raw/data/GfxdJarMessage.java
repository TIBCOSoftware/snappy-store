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
package com.pivotal.gemfirexd.internal.impl.store.raw.data;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.util.Arrays;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.catalog.SystemProcedures;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLPreprocessOrPostProcess;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.AbstractGfxdReplayableMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.execute.GfxdRemoteJarUtil;
import com.pivotal.gemfirexd.internal.io.StorageFile;
import com.pivotal.gemfirexd.internal.io.StorageRandomAccessFile;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

public final class GfxdJarMessage extends AbstractGfxdReplayableMessage
    implements StorageFile, GfxdDDLPreprocessOrPostProcess {

  private static final long serialVersionUID = 8648222850391801895L;

  public static final int JAR_INSTALL = 1;

  public static final int JAR_REMOVE = 2;

  public static final int JAR_REPLACE = 4;

  private long id;

  private long oldId;

  private int type;

  private byte[] jar_bytes;

  // TODO: volatile is not required as all this will
  // happen inside dd write lock and hence flushing to
  // memory will automatically happen. Still check once more.
  private volatile boolean sendBytes = true;

  private String schemaName;

  private String sqlName;

  private String fullName;

  static final String REGION_FOR_CONFLATION = "__GFXD_INTERNAL_JAR";

  public GfxdJarMessage() {
  }

  public GfxdJarMessage(String alias, int type, byte[] bytes,
      LanguageConnectionContext lcc) throws StandardException {
    this.type = type;
    this.jar_bytes = bytes;
    String[] ret = getSchemaName(alias, lcc);
    this.schemaName = ret[0];
    this.sqlName = ret[1];
    this.fullName = this.schemaName + '.' + this.sqlName;
    assert !(preprocess() && postprocess());
  }

  public void setOldId(long oldId) {
    this.oldId = oldId;
  }

  public void setCurrId(long currId) {
    this.id = currId;
  }

  public final long getId() {
    return this.id;
  }

  @Override
  public final void appendFields(final StringBuilder sb) {
    sb.append(", ID=").append(getId()).append(", oldID=").append(this.oldId)
        .append(", type=").append(this.type).append(", name=")
        .append(getName());
//        .append(getName()).append(", jar_bytes: ").append(Arrays.toString(this.jar_bytes));
  }

  @Override
  public void execute() throws StandardException {
    EmbedConnection conn = null;
    boolean contextSet = false;
    try {
      conn = GemFireXDUtils.getTSSConnection(true, true, false);
      conn.getTR().setupContextStack();
      contextSet = true;
      final LanguageConnectionContext lcc = conn.getLanguageConnection();

      if (GemFireXDUtils.TraceApplicationJars) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
            "this.schemaName: " + this.schemaName
                + " schema name from alias is " + this.schemaName);
      }

      switch (this.type) {
        case JAR_INSTALL:
          try {
            GfxdRemoteJarUtil.install(lcc, this.schemaName, this.sqlName,
                getId());
          } catch (StandardException se) {
            // can get a ALREADY_EXISTS exception at this stage due to
            // INSTALL+REPLACE conflation; retry with replace since
            // genuine errors will be caught at source itself
            if (this.oldId != 0 && SQLState.LANG_OBJECT_ALREADY_EXISTS_IN_OBJECT
                .equals(se.getMessageId())) {
              GfxdRemoteJarUtil.replace(lcc, this.schemaName, this.sqlName,
                  getId(), this.oldId);
            }
            else {
              throw se;
            }
          }
          break;

        case JAR_REMOVE:
          GfxdRemoteJarUtil.drop(lcc, this.schemaName, this.sqlName, getId());
          break;

        case JAR_REPLACE:
          try {
            GfxdRemoteJarUtil.replace(lcc, this.schemaName, this.sqlName,
                getId(), this.oldId);
          } catch (StandardException se) {
            // can get a DOES_NOT_EXIST exception at this stage due to
            // INSTALL+REPLACE not getting conflated in region (e.g. recovery
            // from older DataDictionary version or conflation not possible due
            // to crossing replay batch boundary); retry with install since
            // genuine errors will be caught at source itself
            if (SQLState.LANG_FILE_DOES_NOT_EXIST.equals(se.getMessageId())) {
              GfxdRemoteJarUtil.install(lcc, this.schemaName, this.sqlName,
                  getId());
            }
            else {
              throw se;
            }
          }
          break;

        default:
          throw new IllegalStateException(
              "GfxdJarMessage#exception: could not handle type " + this.type);
      }
    } catch (Exception ex) {
      if (GemFireXDUtils.TraceApplicationJars) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
            "GfxdJarMessage#execute: exception encountered", ex);
      }
      if (GemFireXDUtils.retryToBeDone(ex)) {
        throw new InternalFunctionInvocationTargetException(ex);
      }
      throw new FunctionExecutionException(ex);
    } finally {
      if (contextSet) {
        try {
          conn.internalCommit();
        } catch (SQLException ex) {
          if (GemFireXDUtils.TraceApplicationJars) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
                "GfxdJarMessage#execute: exception encountered in commit", ex);
          }
        }
        conn.getTR().restoreContextStack();
      }
    }
  }

  public static String[] getSchemaName(String alias,
      LanguageConnectionContext lcc) throws StandardException {
    String[] st = IdUtil.parseMultiPartSQLIdentifier(alias);
    String schemaName;
    String sqlName;

    if (st.length == 1) {
      schemaName = lcc.getCurrentSchemaName();
      sqlName = st[0];
      st = new String[2];
    }
    else {
      schemaName = st[0];
      sqlName = st[1];
    }
    SystemProcedures.checkJarSQLName(sqlName);
    st[0] = schemaName;
    st[1] = sqlName;
    return st;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean shouldBeConflated() {
    return this.type == JAR_REMOVE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean shouldBeMerged() {
    return this.type == JAR_REPLACE
        // on remote nodes the message sent out is an install after
        // a REPLACE is merged with INSTALL
        || this.type == JAR_INSTALL;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean merge(Conflatable existing) {
    if (existing instanceof GfxdJarMessage
        && ((GfxdJarMessage)existing).type != JAR_REMOVE) {
      // for replace just keep "this" as the merge result
      // change the type to INSTALL
      this.type = JAR_INSTALL;
      return true;
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getRegionToConflate() {
    return this.schemaName + '.' + REGION_FOR_CONFLATION;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getKeyToConflate() {
    return this.sqlName;
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
    return true;
  }

  public boolean postprocess() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSQLStatement() {
    final StringBuilder sb = new StringBuilder();
    switch (this.type) {
      case JAR_INSTALL:
        return sb.append("CALL SQLJ.INSTALL_JAR_BYTES(x'").append(
            ClientSharedUtils.toHexString(this.jar_bytes, 0, jar_bytes.length))
            .append("','").append(getName()).append("')").toString();
      case JAR_REPLACE:
        return sb.append("CALL SQLJ.REPLACE_JAR_BYTES(x'").append(
            ClientSharedUtils.toHexString(this.jar_bytes, 0, jar_bytes.length))
            .append("','").append(getName()).append("')").toString();
      case JAR_REMOVE:
        return sb.append("CALL SQLJ.REMOVE_JAR('").append(getName())
            .append("', 0)").toString();
      default:
        throw new IllegalStateException(
            "GfxdJarMessage#getSQLStatement: could not handle type "
                + this.type);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSchemaName() {
    return this.schemaName;
  }

  @Override
  public byte getGfxdID() {
    return GfxdSerializable.JAR_MSG;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    super.toData(out);

    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarMessage#toData: this.sendBytes " + this.sendBytes
              + " and jar bytes " + Arrays.toString(this.jar_bytes));
    }

    out.writeLong(this.id);
    DataSerializer.writeString(this.schemaName, out);
    DataSerializer.writeString(this.sqlName, out);
    DataSerializer.writeByte((byte)this.type, out);

    if (this.type != JAR_REMOVE) {
      if (this.sendBytes) {
        if (GemFireXDUtils.TraceApplicationJars) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
              "GfxdJarMessage#toData: writing byte array "
                  + Arrays.toString(this.jar_bytes));
        }
        DataSerializer.writeByteArray(this.jar_bytes, out);
      }
      else {
        if (GemFireXDUtils.TraceApplicationJars) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
              "GfxdJarMessage#toData: sending null jar bytes "
                  + Arrays.toString(this.jar_bytes));
        }
        DataSerializer.writeByteArray(null, out);
      }
      DataSerializer.writeLong(this.oldId, out);
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);

    this.id = in.readLong();
    this.schemaName = DataSerializer.readString(in);
    this.sqlName = DataSerializer.readString(in);
    int dotIndex;
    // for 1.0.x versions the sqlName written was the full name
    final Version v = InternalDataSerializer.getVersionForDataStream(in);
    final boolean isVer11 = (Version.SQLF_1099.compareTo(v) <= 0);
    if (isVer11 || (dotIndex = this.sqlName.indexOf('.')) < 0) {
      this.fullName = this.schemaName + '.' + this.sqlName;
    }
    else {
      this.fullName = this.sqlName;
      this.sqlName = this.sqlName.substring(dotIndex + 1);
    }
    this.type = DataSerializer.readByte(in);

    if (this.type != JAR_REMOVE) {
      this.jar_bytes = DataSerializer.readByteArray(in);
      // older versions sent oldId only for REPLACE
      if (isVer11 || this.type == JAR_REPLACE) {
        this.oldId = DataSerializer.readLong(in);
      }
    }
    else {
      this.jar_bytes = null;
    }
  }

  @Override
  public String[] list() {
    return null;
  }

  @Override
  public boolean canWrite() {
    return false;
  }

  @Override
  public boolean exists() {
    return true;
  }

  @Override
  public boolean isDirectory() {
    return false;
  }

  @Override
  public boolean delete() {
    return false;
  }

  @Override
  public boolean deleteAll() {
    return false;
  }

  @Override
  public String getPath() {
    return null;
  }

  @Override
  public String getCanonicalPath() throws IOException {
    return null;
  }

  @Override
  public final String getName() {
    return this.fullName;
  }

  @Override
  public URL getURL() throws MalformedURLException {
    return null;
  }

  @Override
  public boolean createNewFile() throws IOException {
    return false;
  }

  @Override
  public boolean renameTo(StorageFile newName) {
    return false;
  }

  @Override
  public boolean mkdir() {
    return false;
  }

  @Override
  public boolean mkdirs() {
    return false;
  }

  @Override
  public long length() {
    return 0;
  }

  @Override
  public StorageFile getParentDir() {
    return null;
  }

  @Override
  public boolean setReadOnly() {
    return false;
  }

  @Override
  public OutputStream getOutputStream() throws FileNotFoundException {
    return null;
  }

  @Override
  public OutputStream getOutputStream(boolean append)
      throws FileNotFoundException {
    return null;
  }

  @Override
  public InputStream getInputStream() throws FileNotFoundException {
    ByteArrayInputStream bis = new ByteArrayInputStream(this.jar_bytes);
    return bis;
  }

  @Override
  public int getExclusiveFileLock() {
    return StorageFile.NO_FILE_LOCK_SUPPORT;
  }

  @Override
  public void releaseExclusiveFileLock() {

  }

  @Override
  public StorageRandomAccessFile getRandomAccessFile(String mode)
      throws FileNotFoundException {
    return null;
  }

  public void sendByteArray(boolean b) {
    this.sendBytes = b;
  }
}
