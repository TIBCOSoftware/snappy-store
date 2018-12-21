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

package com.pivotal.gemfirexd.internal.engine.ddl;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.*;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

/**
 * Implementatation of {@link Conflatable} that encapsulates a DDL operation.
 * 
 * @author swale
 */
public final class DDLConflatable extends GfxdDataSerializable implements
    ReplayableConflatable, GfxdDDLPreprocess {

  private static final long serialVersionUID = -7222789225768258894L;

  private static final byte HAS_AUTHID = 0x01;

  private static final byte HAS_AUTHID_IN_TABLE = 0x02;

  private static final byte HAS_ADDITIONAL_ARGS = 0x04;

  private static final byte IS_CREATE_TABLE = 0x08;

  /**
   * used for 1.0.2 compability, so do not remove even though looks duplication
   * of {@link #isDropStatement}; we want to keep DataDictionary format
   * consistent (except for the blip in 1.0.2 release), so have the
   * {@link #isDropStatement} written/read as a separate boolean rather than as
   * part of flags
   */
  private static final byte IS_DROP_TABLE = 0x10;

  private static final byte IS_ALTER_TABLE = 0x20;

  private static final byte IS_CREATE_INDEX = 0x40;

  /**
   * this flag is for backward compatibility where old versions don't have ddlId
   * in the DDLConflatable
   */
  private static final byte HAS_DDLID = (byte)0x80;

  private static final byte PEEK_NONE = 0;

  private static final byte PEEK_EXECUTING = 0x1;

  private static final byte PEEK_SKIP = 0x2;

  private static final Pattern isCreateSchemaPattern = Pattern
      .compile("^\\s*CREATE\\s+SCHEMA\\s+.*$", Pattern.CASE_INSENSITIVE
          | Pattern.DOTALL);

  private boolean isDropStatement;

  private String fullTableName;

  private String defaultSchema;

  private String objectName;

  private String sqlText;

  private byte flags;

  private Object additionalArgs;

  private long ddlId;

  private volatile byte localState;

  // additional set of flags that have gone beyond the "flags" byte
  private int additionalFlags;

  // fields that are serialized with additionalFlags set
  private String colocatedWithTable;
  /** piggy-backed DDLConflatable for an implicit schema creation */
  private DDLConflatable implicitSchema;
  private long implicitSchemaSequenceId;

  private String tableName;

  private boolean isHDFSPersistent;

  private String hdfsStoreName;

  /** now each DDL will be versioned for any adjustments in recovery etc */
  private short ddlVersion;

  // the bitmasks for "additionalFlags"
  private static final int F_HAS_COLOCATED_WITH = 0x1;
  private static final int F_HAS_IMPLICIT_SCHEMA = 0x2;
  private static final int F_IS_DROP_FK_CONSTRAINT = 0x04;
  private static final int F_IS_ADD_FK_CONSTRAINT = 0x08;
  private static final int F_DEFAULT_PERSISTENT = 0x10;
  /** true if metastore should be stored in datadictionary */
  private static final int F_METASTORE_IN_DD = 0x20;
  /** true if ddl is a create disk store statement */
  private static final int F_IS_CREATE_DISKSTORE = 0x40;

  private String constraintName; 
  private Set<String> droppedFKConstraints = null;

  private final static Version[] serializationVersions = new Version[] {
      Version.GFXD_1011, Version.GFXD_1302 };

  /** empty constructor for deserialization */
  public DDLConflatable() {
  }

  public DDLConflatable(String sqlText, String defaultSchema,
      DDLConstantAction constantAction, Object additionalArgs,
      DDLConflatable implicitSchema, long ddlId, boolean queueInitialized,
      LanguageConnectionContext lcc) {
    // by default newer versions always store metastore in datadictionary
    this.additionalFlags = F_METASTORE_IN_DD;
    if (constantAction instanceof CreateDiskStoreConstantAction) {
      this.additionalFlags = GemFireXDUtils.set(this.additionalFlags,
          F_IS_CREATE_DISKSTORE);
    }
    if (constantAction instanceof CreateTableConstantAction) {
      this.flags = GemFireXDUtils.set(this.flags, IS_CREATE_TABLE);
      this.colocatedWithTable = ((CreateTableConstantAction)constantAction)
          .getColocatedWithTable();
      if (this.colocatedWithTable != null) {
        this.additionalFlags = GemFireXDUtils.set(this.additionalFlags,
            F_HAS_COLOCATED_WITH);
      }
    }
    if (implicitSchema != null) {
      this.implicitSchema = implicitSchema;
      this.additionalFlags = GemFireXDUtils.set(this.additionalFlags,
          F_HAS_IMPLICIT_SCHEMA);
    }
    if (lcc != null && lcc.isDefaultPersistent()) {
      this.additionalFlags = GemFireXDUtils.set(this.additionalFlags,
          F_DEFAULT_PERSISTENT);
    }
    this.isDropStatement = constantAction.isDropStatement();
    // for ALTER TABLE we need to skip the initialization of regions till
    // after ALTER TABLE to ensure proper meta-data for any updates
    if (constantAction instanceof AlterTableConstantAction
        && !((AlterTableConstantAction)constantAction).isTruncateTable()) {
      this.flags = GemFireXDUtils.set(this.flags, IS_ALTER_TABLE);
      ConstraintConstantAction[] constraintConstantActions = 
          ((AlterTableConstantAction)constantAction).getConstraintConstantActions();
      if (constraintConstantActions != null) {
        for (int i = 0; i < constraintConstantActions.length; i++) {
          ConstraintConstantAction c = constraintConstantActions[i];
          
          if (c instanceof DropConstraintConstantAction && 
              ((DropConstraintConstantAction)c).isForeignKeyConstraint()) {
            this.additionalFlags = GemFireXDUtils.set(this.additionalFlags,
                F_IS_DROP_FK_CONSTRAINT);
            constraintName = ((DropConstraintConstantAction) c)
                .getConstraintName();
          } else if (c instanceof CreateConstraintConstantAction &&
              ((CreateConstraintConstantAction)c).getConstraintType() ==
              DataDictionary.FOREIGNKEY_CONSTRAINT) {
            this.additionalFlags = GemFireXDUtils.set(this.additionalFlags,
                F_IS_ADD_FK_CONSTRAINT);
            constraintName = ((CreateConstraintConstantAction) c)
                .getConstraintName();
          }
        }
      }
    }
    else if (constantAction instanceof CreateIndexConstantAction) {
      this.flags = GemFireXDUtils.set(this.flags, IS_CREATE_INDEX);
    }
    String schemaName = constantAction.getSchemaName();
    this.tableName = constantAction.getTableName();

    if (schemaName != null) {
      this.fullTableName = (this.tableName != null ? (this.tableName.indexOf('.') < 0
          ? (schemaName + '.' + this.tableName) : this.tableName) : schemaName);
    }
    else {
      this.fullTableName = null;
    }
    this.objectName = constantAction.getObjectName();
    if (isCreateTable()) {
      // #50116: in the case the query is of the form 'create table t1 as
      // select * from t2 with no data' get a generated SQL text that contains
      // actual column definitions instead of the select clause
      this.sqlText = ((CreateTableConstantAction) constantAction)
          .getSQLTextForCTAS();
      if (this.sqlText != null) {
        if (GemFireXDUtils.TraceDDLQueue || GemFireXDUtils.TraceQuery) {
          SanityManager.DEBUG_PRINT( "Info",
              "DDLConflatable#ctor added internally generated sqlText for"
                  + " create table query. sqlText="
                  + ((CreateTableConstantAction) constantAction)
                      .getSQLTextForCTAS());
        }
      }
    }
    
    if (this.sqlText == null) {
      this.sqlText = sqlText;
    }
//    if (authorizationId != null
//        && !Property.DEFAULT_USER_NAME.equals(authorizationId)) {
//      if (authorizationId.equals(schemaName)) {
//        this.flags = GemFireXDUtils.set(this.flags, HAS_AUTHID_IN_TABLE);
//      }
//      else {
//        this.authorizationId = authorizationId;
//        this.flags = GemFireXDUtils.set(this.flags, HAS_AUTHID);
//      }
//    }
    /*[soubhik] lets put the defaultSchema always which in the remote VM
     * will be set first before executing this ddl.
     * 
     * A fully qualified table name might be executed from a different 
     * default schema.
     */
    this.defaultSchema = defaultSchema;
    if (additionalArgs != null) {
      this.additionalArgs = additionalArgs;
      this.flags = GemFireXDUtils.set(this.flags, HAS_ADDITIONAL_ARGS);
    }
    // if queue has not yet been initialized when running config-scripts
    // then mark this as a DDL that needs to be skipped in replay since
    // this will be locally executed as part of config-scripts
    this.localState = queueInitialized ? PEEK_NONE : PEEK_SKIP;
    this.ddlId = ddlId;
    this.isHDFSPersistent = checkifHDFSPersistent(constantAction);
    initDefaultFlags();
    this.ddlVersion = GemFireXDUtils.getCurrentDDLVersion().ordinal();

    assert !this.isDropStatement
    // [sjigyasu] Relax the assertion for drop statements with "if exists"
    || (this.isDropStatement && constantAction.isDropIfExists())
    || (this.fullTableName != null) : "Expected "
        + "a non-null schema/table name when conflation is requested";
  }
  
  /**
   * Returns if the DDL command is to be persisted on HDFS. 
   * @param ddlAction
   * @return
   */
  public boolean checkifHDFSPersistent(ConstantAction ddlAction) {
    if (ddlAction instanceof CreateTableConstantAction ||
        (ddlAction instanceof AlterTableConstantAction
            && !((AlterTableConstantAction)ddlAction).isTruncateTable())  ||
        ddlAction instanceof CreateAliasConstantAction || 
        ddlAction instanceof DropAliasConstantAction || 
        ddlAction instanceof DropSchemaConstantAction || 
        ddlAction instanceof CreateSchemaConstantAction) {
      return true;
    }
    if (ddlAction instanceof CreateHDFSStoreConstantAction) {
      this.hdfsStoreName = ((CreateHDFSStoreConstantAction)ddlAction).getHDFSStoreName();
      return true;
    }
    if (ddlAction instanceof AlterHDFSStoreConstantAction) {
      this.hdfsStoreName = ((AlterHDFSStoreConstantAction)ddlAction).getHDFSStoreName();
      return true;
    }
    return false;
  }
  
  public boolean isHDFSPersistent() {
    return isHDFSPersistent;
  }
  
  public String getTableName() {
    return this.tableName;
  }

  public String getHDFSStoreName() {
    return this.hdfsStoreName;
  }

  /**
   * set the default flags now always set in {@link #flags}; this should even be
   * set after fromData so that recovery from older versions then sending as new
   * version works correctly
   */
  private void initDefaultFlags() {
    this.flags = GemFireXDUtils.set(this.flags, HAS_AUTHID);
    this.flags = GemFireXDUtils.set(this.flags, HAS_DDLID);
  }

  public final boolean isCreateTable() {
    return GemFireXDUtils.isSet(this.flags, IS_CREATE_TABLE);
  }

  public final boolean isDropStatement() {
    return this.isDropStatement;
  }

  public final boolean isAlterTable() {
    return GemFireXDUtils.isSet(this.flags, IS_ALTER_TABLE);
  }
  
  public final boolean isAlterTableDropFKConstraint() {
    return GemFireXDUtils.isSet(this.additionalFlags, 
        F_IS_DROP_FK_CONSTRAINT);
  }
  
  public final boolean isAlterTableAddFKConstraint() {
    return GemFireXDUtils.isSet(this.additionalFlags, 
        F_IS_ADD_FK_CONSTRAINT);
  }

  public final boolean defaultPersistent() {
    return GemFireXDUtils.isSet(this.additionalFlags,
        F_DEFAULT_PERSISTENT);
  }

  public final boolean persistMetaStoreInDataDictionary() {
    return GemFireXDUtils.isSet(this.additionalFlags, F_METASTORE_IN_DD);
  }

  public final boolean isCreateIndex() {
    return GemFireXDUtils.isSet(this.flags, IS_CREATE_INDEX);
  }

  public final boolean isCreateDiskStore() {
    return GemFireXDUtils.isSet(this.additionalFlags,
        F_IS_CREATE_DISKSTORE);
  }

  public boolean shouldBeConflated() {
    return this.isDropStatement;
  }

  /**
   * {@inheritDoc}
   * 
   */
  @Override
  public boolean shouldBeMerged() {
    //#50116: currently return true when there is an 
    //'alter table drop fk constarint' statement. 
    return this.isAlterTableDropFKConstraint();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean merge(Conflatable existing) {
    if (GemFireXDUtils.TraceConflation | DistributionManager.VERBOSE) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION, 
          "DDLConflatable#merge called this=" + this + 
          " existing conflatable=" + existing );
    }
    assert ((DDLConflatable) existing).isAlterTableDropFKConstraint();
    // #50116: 
    // don't actually merge instead for 'CREATE TABLE' and 'ALTER TABLE
    // ADD FK CONSTRAINT', just note the constraint that was dropped so that
    // we can ignore error in the DDL replay for them in case parent on which 
    // FK was defined is dropped
    if (this.isCreateTable()
        || (this.isAlterTableAddFKConstraint() && 
            this.getConstraintName().equals(((DDLConflatable) existing)
            .getConstraintName()))) {
      this.addToDroppedFKConstraints(((DDLConflatable) existing).getConstraintName());
      return true;
    }
    return false;
  }

  public boolean shouldDelayRegionInitialization() {
    return GemFireXDUtils.isSet(this.flags, IS_ALTER_TABLE);
  }

  public String getRegionToConflate() {
    return this.fullTableName;
  }

  public String getKeyToConflate() {
    return this.objectName;
  }

  public String getValueToConflate() {
    return this.sqlText;
  }

  public Object getAdditionalArgs() {
    return this.additionalArgs;
  }

  public long getId() {
    return this.ddlId;
  }

  public String getCurrentSchema() {
    if (GemFireXDUtils.isSet(this.flags, HAS_AUTHID_IN_TABLE)) {
      final int dotIndex = this.fullTableName.indexOf('.');
      if (dotIndex == -1) {
        return this.fullTableName;
      }
      return this.fullTableName.substring(0, dotIndex);
    }
    return this.defaultSchema;
  }
  
  /**
   * returns the schema for a table 
   * 
   */
  public String getSchemaForTable() {
    assert isCreateTable() || isAlterTable();
    return getSchemaForTable_internal();
  }

  private String getSchemaForTable_internal() {
    if (this.fullTableName != null) {
      final int dotIndex = this.fullTableName.indexOf('.');
      if (dotIndex == -1) {
        return null;
      }
      return this.fullTableName.substring(0, dotIndex);
    }
    return null;
  }

  public String getSchemaForTableNoThrow() {
    return getSchemaForTable_internal();
  }

  public final String getColocatedWithTable() {
    return this.colocatedWithTable;
  }

  public final DDLConflatable getImplicitSchema() {
    return this.implicitSchema;
  }

  public final long getImplicitSchemaSequenceId() {
    return this.implicitSchemaSequenceId;
  }

  public final void setImplicitSchemaSequenceId(long sequenceId) {
    this.implicitSchemaSequenceId = sequenceId;
  }

  public void setLatestValue(Object value) {
    throw new AssertionError("DDLConflatable#setLatestValue: "
        + "not expected to be invoked");
  }

  public EventID getEventId() {
    throw new AssertionError("DDLConflatable#getEventId: "
        + "not expected to be invoked");
  }
  
  public String getConstraintName() {
    return this.constraintName;
  }

  public short getDDLVersion() {
    return ddlVersion;
  }
  
  /**
   * Mark this DDL has having started the process of execution in DDL replay
   * (essentially when it has been removed from the DDL queue locally). Requires
   * to be guarded by {@link GemFireStore#acquireDDLReplayLock}.
   */
  public final void markExecuting() {
    this.localState |= PEEK_EXECUTING;
  }

  /**
   * Check if this DDL has been marked as having completed execution. Requires
   * to be guarded by {@link GemFireStore#acquireDDLReplayLock}.
   */
  public final boolean isExecuting() {
    return (this.localState & PEEK_EXECUTING) != 0;
  }

  /**
   * Returns true if this DDL has been marked for skip in local execution since
   * it was inserted when running config-scripts on this node itself.
   */
  @Override
  public final boolean skipInLocalExecution() {
    return (this.localState & PEEK_SKIP) != 0;
  }

  private boolean hasAuthorizationId() {
    return GemFireXDUtils.isSet(this.flags, HAS_AUTHID);
  }

  public boolean isCreateSchemaText() {
    return isCreateSchemaPattern.matcher(this.sqlText).find();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean preprocess() {
    // only "CREATE SCHEMA" is pre-processed
    return isCreateSchemaText();
  }

  @Override
  public int hashCode() {
    // ddlId is zero for old product version in which case use SQL text
    final long id = this.ddlId;
    if (id != 0) {
      return (int)(id ^ (id >>> 32));
    }
    else {
      return this.sqlText.hashCode();
    }
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof DDLConflatable) {
      final DDLConflatable otherDDL = (DDLConflatable)other;
      // ddlId is zero for old product version in which case use SQL text
      if (this.ddlId != 0 && otherDDL.ddlId != 0) {
        return (this.ddlId == otherDDL.ddlId);
      }
      else {
        return this.sqlText.equals(otherDDL.sqlText);
      }
    }
    else {
      return false;
    }
  }
  
  public void addToDroppedFKConstraints(String fk) {
    if (this.droppedFKConstraints == null) {
      this.droppedFKConstraints = new HashSet<String>();
    }
    this.droppedFKConstraints.add(fk);
  }
  
  public Set<String> getDroppedFKConstraints() {
    return this.droppedFKConstraints;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void appendFields(StringBuilder sb) {
    sb.append(" [").append(this.ddlId).append(']');
    sb.append(" SQLText [");
    sb.append(GemFireXDUtils.maskGenericPasswordFromSQLString(this.sqlText));
    sb.append("] fullTableName=").append(this.fullTableName);
    sb.append(";defaultSchema=").append(this.defaultSchema);
    sb.append(";objectName=").append(this.objectName);
    sb.append(";isCreateTable=").append(isCreateTable());
    sb.append(";isDropStatement=").append(isDropStatement());
    sb.append(";isAlterTable=").append(
        GemFireXDUtils.isSet(this.flags, IS_ALTER_TABLE));
    sb.append(";isAlterTableDropFKConstraint=").append(
        isAlterTableDropFKConstraint());
    sb.append(";constraintName=").append(this.constraintName);
    sb.append(";droppedFKConstraints=").append(this.droppedFKConstraints);
    if (this.colocatedWithTable != null) {
      sb.append(";colocateWith=").append(this.colocatedWithTable);
    }
    if (this.implicitSchema != null) {
      sb.append(";implicitSchemaCreated=").append(
          this.implicitSchema.getRegionToConflate());
      sb.append(";implicitSchemaSequenceId=").append(
          this.implicitSchemaSequenceId);
    }
    sb.append(";flags=0x").append(Integer.toHexString(this.flags & 0xFF));
    sb.append(";additionalFlags=0x").append(
        Integer.toHexString(this.additionalFlags));
    if (this.additionalArgs != null) {
      sb.append(";additionalArgs=").append(this.additionalArgs);
    }
    sb.append(";skipLocalPeek=").append(this.localState);
    sb.append(";isHDFSPersistent=").append(this.isHDFSPersistent);
    sb.append(";hdfsstoreName=").append(this.hdfsStoreName);
  }

  /**
   * Returns a string representation for {@link DDLConflatable}.
   */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("{DDLConflatable");
    appendFields(sb);
    sb.append('}');
    return sb.toString();
  }

  @Override
  public byte getGfxdID() {
    return GfxdSerializable.DDL_CONFLATABLE;
  }
  
  public Version[] getSerializationVersions() {
    return serializationVersions;
  }

  private void toDataBase(final DataOutput out) throws IOException {
    super.toData(out);
    // this is not part of flags to keep compatible with 1.0/1.0.1
    out.writeBoolean(this.isDropStatement);
    DataSerializer.writeString(this.fullTableName, out);
    DataSerializer.writeString(this.objectName, out);
    DataSerializer.writeString(this.sqlText, out);
    out.writeByte(this.flags);
    if (hasAuthorizationId()) {
      DataSerializer.writeString(this.defaultSchema, out);
    }
    if (this.additionalArgs != null) {
      DataSerializer.writeObject(this.additionalArgs, out);
    }
    InternalDataSerializer.writeUnsignedVL(this.additionalFlags, out);
    if (this.colocatedWithTable != null) {
      DataSerializer.writeString(this.colocatedWithTable, out);
    }
    if (this.implicitSchema != null) {
      InternalDataSerializer.invokeToData(this.implicitSchema, out);
      out.writeLong(this.implicitSchemaSequenceId);
    }
    out.writeLong(this.ddlId);
    out.writeBoolean(this.isHDFSPersistent);
    if (this.isHDFSPersistent) {
      DataSerializer.writeString(this.tableName, out);
      DataSerializer.writeString(this.hdfsStoreName, out);
    }
  }

  /**
   * check and set whether this JVM is already setup to use pre GFXD 1.3.0.2
   * table hashing or else if it is set to use >= 1.3.0.2 hashing then fail
   */
  private void checkAndSetPre1302HashingForTables() {
    ResolverUtils.setUsePre1302Hashing(true);
  }

  public void toDataPre_GFXD_1_0_1_1(final DataOutput out) throws IOException {
    toDataBase(out);
    // use old hashing scheme (#51381)
    checkAndSetPre1302HashingForTables();
  }

  public void toDataPre_GFXD_1_3_0_2(final DataOutput out) throws IOException {
    toDataPre_GFXD_1_0_1_1(out);
    if (isAlterTableDropFKConstraint() || isAlterTableAddFKConstraint()) {
      DataSerializer.writeString(this.constraintName, out);
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    toDataBase(out);
    if (isAlterTableDropFKConstraint() || isAlterTableAddFKConstraint()) {
      DataSerializer.writeString(this.constraintName, out);
    }
    out.writeShort(this.ddlVersion);
  }

  private void fromDataBase(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
    final byte b = in.readByte();
    // check for the 1.0.2 backward compability change first
    // that merged dropStatement in flags
    boolean backward102case = false;
    switch (b) {
      case 0:
        this.isDropStatement = false; // good case
        break;
      case 1:
        this.isDropStatement = true; // good case
        break;
      default: // case for compability with 1.0.2
        backward102case = true;
        break;
    }
    if (backward102case) {
      this.fullTableName = InternalDataSerializer.readString(in, b);
    }
    else {
      this.fullTableName = DataSerializer.readString(in);
    }
    this.objectName = DataSerializer.readString(in);
    this.sqlText = DataSerializer.readString(in);
    this.flags = in.readByte();
    if (backward102case) {
      this.isDropStatement = (this.flags & IS_DROP_TABLE) != 0;
    }
    if (hasAuthorizationId()) {
      this.defaultSchema = DataSerializer.readString(in);
    }
    if ((this.flags & HAS_ADDITIONAL_ARGS) != 0) {
      this.additionalArgs = DataSerializer.readObject(in);
    }
    // handle recovery from older versions that do not have colocatedWith and
    // other newer flags in "additionalFlags"
    Version v = InternalDataSerializer.getVersionForDataStream(in);
    if (Version.SQLF_1099.compareTo(v) <= 0) {
      this.additionalFlags = (int)InternalDataSerializer.readUnsignedVL(in);
      if ((this.additionalFlags & F_HAS_COLOCATED_WITH) != 0) {
        this.colocatedWithTable = DataSerializer.readString(in);
      }
      if ((this.additionalFlags & F_HAS_IMPLICIT_SCHEMA) != 0) {
        this.implicitSchema = new DDLConflatable();
        InternalDataSerializer.invokeFromData(this.implicitSchema, in);
        this.implicitSchemaSequenceId = in.readLong();
      }
    }
    if ((this.flags & HAS_DDLID) != 0) {
      this.ddlId = in.readLong();
    }
    if (Version.GFXD_10.compareTo(v) <= 0) {
      this.isHDFSPersistent = in.readBoolean();
      if (this.isHDFSPersistent) {
        this.tableName = DataSerializer.readString(in);
        this.hdfsStoreName = DataSerializer.readString(in);
      }
    }
    // using the last pre 1.3.0.2 version by default
    this.ddlVersion = Version.GFXD_13.ordinal();
    // set the default flags as per the latest version
    initDefaultFlags();
  }

  public void fromDataPre_GFXD_1_0_1_1(DataInput in) throws IOException,
      ClassNotFoundException {
    fromDataBase(in);
    // use old hashing scheme (#51381)
    checkAndSetPre1302HashingForTables();
  }

  public void fromDataPre_GFXD_1_3_0_2(DataInput in) throws IOException,
      ClassNotFoundException {
    fromDataPre_GFXD_1_0_1_1(in);
    if (isAlterTableDropFKConstraint() || isAlterTableAddFKConstraint()) {
      this.constraintName = DataSerializer.readString(in);
    }
  }

  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    fromDataBase(in);
    if (isAlterTableDropFKConstraint() || isAlterTableAddFKConstraint()) {
      this.constraintName = DataSerializer.readString(in);
    }
    this.ddlVersion = in.readShort();
    if (this.ddlVersion >= Version.GFXD_1302.ordinal()) {
      ResolverUtils.setUseGFXD1302Hashing(true);
    }
    else {
      checkAndSetPre1302HashingForTables();
    }
  }
}
