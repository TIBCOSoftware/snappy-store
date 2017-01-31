/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package io.snappydata.thrift;

import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;

import org.apache.thrift.scheme.TupleScheme;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.server.AbstractNonblockingServer.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.annotation.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked"})
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-01-31")
public class UpdateResult implements org.apache.thrift.TBase<UpdateResult, UpdateResult._Fields>, java.io.Serializable, Cloneable, Comparable<UpdateResult> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("UpdateResult");

  private static final org.apache.thrift.protocol.TField UPDATE_COUNT_FIELD_DESC = new org.apache.thrift.protocol.TField("updateCount", org.apache.thrift.protocol.TType.I32, (short)1);
  private static final org.apache.thrift.protocol.TField BATCH_UPDATE_COUNTS_FIELD_DESC = new org.apache.thrift.protocol.TField("batchUpdateCounts", org.apache.thrift.protocol.TType.LIST, (short)2);
  private static final org.apache.thrift.protocol.TField GENERATED_KEYS_FIELD_DESC = new org.apache.thrift.protocol.TField("generatedKeys", org.apache.thrift.protocol.TType.STRUCT, (short)3);
  private static final org.apache.thrift.protocol.TField NEW_DEFAULT_SCHEMA_FIELD_DESC = new org.apache.thrift.protocol.TField("newDefaultSchema", org.apache.thrift.protocol.TType.STRING, (short)4);
  private static final org.apache.thrift.protocol.TField WARNINGS_FIELD_DESC = new org.apache.thrift.protocol.TField("warnings", org.apache.thrift.protocol.TType.STRUCT, (short)5);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new UpdateResultStandardSchemeFactory());
    schemes.put(TupleScheme.class, new UpdateResultTupleSchemeFactory());
  }

  public int updateCount; // optional
  public List<Integer> batchUpdateCounts; // optional
  public RowSet generatedKeys; // optional
  public String newDefaultSchema; // optional
  public SnappyExceptionData warnings; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    UPDATE_COUNT((short)1, "updateCount"),
    BATCH_UPDATE_COUNTS((short)2, "batchUpdateCounts"),
    GENERATED_KEYS((short)3, "generatedKeys"),
    NEW_DEFAULT_SCHEMA((short)4, "newDefaultSchema"),
    WARNINGS((short)5, "warnings");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // UPDATE_COUNT
          return UPDATE_COUNT;
        case 2: // BATCH_UPDATE_COUNTS
          return BATCH_UPDATE_COUNTS;
        case 3: // GENERATED_KEYS
          return GENERATED_KEYS;
        case 4: // NEW_DEFAULT_SCHEMA
          return NEW_DEFAULT_SCHEMA;
        case 5: // WARNINGS
          return WARNINGS;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __UPDATECOUNT_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.UPDATE_COUNT,_Fields.BATCH_UPDATE_COUNTS,_Fields.GENERATED_KEYS,_Fields.NEW_DEFAULT_SCHEMA,_Fields.WARNINGS};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.UPDATE_COUNT, new org.apache.thrift.meta_data.FieldMetaData("updateCount", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.BATCH_UPDATE_COUNTS, new org.apache.thrift.meta_data.FieldMetaData("batchUpdateCounts", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32))));
    tmpMap.put(_Fields.GENERATED_KEYS, new org.apache.thrift.meta_data.FieldMetaData("generatedKeys", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, RowSet.class)));
    tmpMap.put(_Fields.NEW_DEFAULT_SCHEMA, new org.apache.thrift.meta_data.FieldMetaData("newDefaultSchema", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.WARNINGS, new org.apache.thrift.meta_data.FieldMetaData("warnings", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, SnappyExceptionData.class)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(UpdateResult.class, metaDataMap);
  }

  public UpdateResult() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public UpdateResult(UpdateResult other) {
    __isset_bitfield = other.__isset_bitfield;
    this.updateCount = other.updateCount;
    if (other.isSetBatchUpdateCounts()) {
      List<Integer> __this__batchUpdateCounts = new ArrayList<Integer>(other.batchUpdateCounts);
      this.batchUpdateCounts = __this__batchUpdateCounts;
    }
    if (other.isSetGeneratedKeys()) {
      this.generatedKeys = new RowSet(other.generatedKeys);
    }
    if (other.isSetNewDefaultSchema()) {
      this.newDefaultSchema = other.newDefaultSchema;
    }
    if (other.isSetWarnings()) {
      this.warnings = new SnappyExceptionData(other.warnings);
    }
  }

  public UpdateResult deepCopy() {
    return new UpdateResult(this);
  }

  @Override
  public void clear() {
    setUpdateCountIsSet(false);
    this.updateCount = 0;
    this.batchUpdateCounts = null;
    this.generatedKeys = null;
    this.newDefaultSchema = null;
    this.warnings = null;
  }

  public int getUpdateCount() {
    return this.updateCount;
  }

  public UpdateResult setUpdateCount(int updateCount) {
    this.updateCount = updateCount;
    setUpdateCountIsSet(true);
    return this;
  }

  public void unsetUpdateCount() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __UPDATECOUNT_ISSET_ID);
  }

  /** Returns true if field updateCount is set (has been assigned a value) and false otherwise */
  public boolean isSetUpdateCount() {
    return EncodingUtils.testBit(__isset_bitfield, __UPDATECOUNT_ISSET_ID);
  }

  public void setUpdateCountIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __UPDATECOUNT_ISSET_ID, value);
  }

  public int getBatchUpdateCountsSize() {
    return (this.batchUpdateCounts == null) ? 0 : this.batchUpdateCounts.size();
  }

  public java.util.Iterator<Integer> getBatchUpdateCountsIterator() {
    return (this.batchUpdateCounts == null) ? null : this.batchUpdateCounts.iterator();
  }

  public void addToBatchUpdateCounts(int elem) {
    if (this.batchUpdateCounts == null) {
      this.batchUpdateCounts = new ArrayList<Integer>();
    }
    this.batchUpdateCounts.add(elem);
  }

  public List<Integer> getBatchUpdateCounts() {
    return this.batchUpdateCounts;
  }

  public UpdateResult setBatchUpdateCounts(List<Integer> batchUpdateCounts) {
    this.batchUpdateCounts = batchUpdateCounts;
    return this;
  }

  public void unsetBatchUpdateCounts() {
    this.batchUpdateCounts = null;
  }

  /** Returns true if field batchUpdateCounts is set (has been assigned a value) and false otherwise */
  public boolean isSetBatchUpdateCounts() {
    return this.batchUpdateCounts != null;
  }

  public void setBatchUpdateCountsIsSet(boolean value) {
    if (!value) {
      this.batchUpdateCounts = null;
    }
  }

  public RowSet getGeneratedKeys() {
    return this.generatedKeys;
  }

  public UpdateResult setGeneratedKeys(RowSet generatedKeys) {
    this.generatedKeys = generatedKeys;
    return this;
  }

  public void unsetGeneratedKeys() {
    this.generatedKeys = null;
  }

  /** Returns true if field generatedKeys is set (has been assigned a value) and false otherwise */
  public boolean isSetGeneratedKeys() {
    return this.generatedKeys != null;
  }

  public void setGeneratedKeysIsSet(boolean value) {
    if (!value) {
      this.generatedKeys = null;
    }
  }

  public String getNewDefaultSchema() {
    return this.newDefaultSchema;
  }

  public UpdateResult setNewDefaultSchema(String newDefaultSchema) {
    this.newDefaultSchema = newDefaultSchema;
    return this;
  }

  public void unsetNewDefaultSchema() {
    this.newDefaultSchema = null;
  }

  /** Returns true if field newDefaultSchema is set (has been assigned a value) and false otherwise */
  public boolean isSetNewDefaultSchema() {
    return this.newDefaultSchema != null;
  }

  public void setNewDefaultSchemaIsSet(boolean value) {
    if (!value) {
      this.newDefaultSchema = null;
    }
  }

  public SnappyExceptionData getWarnings() {
    return this.warnings;
  }

  public UpdateResult setWarnings(SnappyExceptionData warnings) {
    this.warnings = warnings;
    return this;
  }

  public void unsetWarnings() {
    this.warnings = null;
  }

  /** Returns true if field warnings is set (has been assigned a value) and false otherwise */
  public boolean isSetWarnings() {
    return this.warnings != null;
  }

  public void setWarningsIsSet(boolean value) {
    if (!value) {
      this.warnings = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case UPDATE_COUNT:
      if (value == null) {
        unsetUpdateCount();
      } else {
        setUpdateCount((Integer)value);
      }
      break;

    case BATCH_UPDATE_COUNTS:
      if (value == null) {
        unsetBatchUpdateCounts();
      } else {
        setBatchUpdateCounts((List<Integer>)value);
      }
      break;

    case GENERATED_KEYS:
      if (value == null) {
        unsetGeneratedKeys();
      } else {
        setGeneratedKeys((RowSet)value);
      }
      break;

    case NEW_DEFAULT_SCHEMA:
      if (value == null) {
        unsetNewDefaultSchema();
      } else {
        setNewDefaultSchema((String)value);
      }
      break;

    case WARNINGS:
      if (value == null) {
        unsetWarnings();
      } else {
        setWarnings((SnappyExceptionData)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case UPDATE_COUNT:
      return getUpdateCount();

    case BATCH_UPDATE_COUNTS:
      return getBatchUpdateCounts();

    case GENERATED_KEYS:
      return getGeneratedKeys();

    case NEW_DEFAULT_SCHEMA:
      return getNewDefaultSchema();

    case WARNINGS:
      return getWarnings();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case UPDATE_COUNT:
      return isSetUpdateCount();
    case BATCH_UPDATE_COUNTS:
      return isSetBatchUpdateCounts();
    case GENERATED_KEYS:
      return isSetGeneratedKeys();
    case NEW_DEFAULT_SCHEMA:
      return isSetNewDefaultSchema();
    case WARNINGS:
      return isSetWarnings();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof UpdateResult)
      return this.equals((UpdateResult)that);
    return false;
  }

  public boolean equals(UpdateResult that) {
    if (that == null)
      return false;

    boolean this_present_updateCount = true && this.isSetUpdateCount();
    boolean that_present_updateCount = true && that.isSetUpdateCount();
    if (this_present_updateCount || that_present_updateCount) {
      if (!(this_present_updateCount && that_present_updateCount))
        return false;
      if (this.updateCount != that.updateCount)
        return false;
    }

    boolean this_present_batchUpdateCounts = true && this.isSetBatchUpdateCounts();
    boolean that_present_batchUpdateCounts = true && that.isSetBatchUpdateCounts();
    if (this_present_batchUpdateCounts || that_present_batchUpdateCounts) {
      if (!(this_present_batchUpdateCounts && that_present_batchUpdateCounts))
        return false;
      if (!this.batchUpdateCounts.equals(that.batchUpdateCounts))
        return false;
    }

    boolean this_present_generatedKeys = true && this.isSetGeneratedKeys();
    boolean that_present_generatedKeys = true && that.isSetGeneratedKeys();
    if (this_present_generatedKeys || that_present_generatedKeys) {
      if (!(this_present_generatedKeys && that_present_generatedKeys))
        return false;
      if (!this.generatedKeys.equals(that.generatedKeys))
        return false;
    }

    boolean this_present_newDefaultSchema = true && this.isSetNewDefaultSchema();
    boolean that_present_newDefaultSchema = true && that.isSetNewDefaultSchema();
    if (this_present_newDefaultSchema || that_present_newDefaultSchema) {
      if (!(this_present_newDefaultSchema && that_present_newDefaultSchema))
        return false;
      if (!this.newDefaultSchema.equals(that.newDefaultSchema))
        return false;
    }

    boolean this_present_warnings = true && this.isSetWarnings();
    boolean that_present_warnings = true && that.isSetWarnings();
    if (this_present_warnings || that_present_warnings) {
      if (!(this_present_warnings && that_present_warnings))
        return false;
      if (!this.warnings.equals(that.warnings))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_updateCount = true && (isSetUpdateCount());
    list.add(present_updateCount);
    if (present_updateCount)
      list.add(updateCount);

    boolean present_batchUpdateCounts = true && (isSetBatchUpdateCounts());
    list.add(present_batchUpdateCounts);
    if (present_batchUpdateCounts)
      list.add(batchUpdateCounts);

    boolean present_generatedKeys = true && (isSetGeneratedKeys());
    list.add(present_generatedKeys);
    if (present_generatedKeys)
      list.add(generatedKeys);

    boolean present_newDefaultSchema = true && (isSetNewDefaultSchema());
    list.add(present_newDefaultSchema);
    if (present_newDefaultSchema)
      list.add(newDefaultSchema);

    boolean present_warnings = true && (isSetWarnings());
    list.add(present_warnings);
    if (present_warnings)
      list.add(warnings);

    return list.hashCode();
  }

  @Override
  public int compareTo(UpdateResult other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetUpdateCount()).compareTo(other.isSetUpdateCount());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetUpdateCount()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.updateCount, other.updateCount);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetBatchUpdateCounts()).compareTo(other.isSetBatchUpdateCounts());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBatchUpdateCounts()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.batchUpdateCounts, other.batchUpdateCounts);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetGeneratedKeys()).compareTo(other.isSetGeneratedKeys());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetGeneratedKeys()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.generatedKeys, other.generatedKeys);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetNewDefaultSchema()).compareTo(other.isSetNewDefaultSchema());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetNewDefaultSchema()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.newDefaultSchema, other.newDefaultSchema);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetWarnings()).compareTo(other.isSetWarnings());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetWarnings()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.warnings, other.warnings);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("UpdateResult(");
    boolean first = true;

    if (isSetUpdateCount()) {
      sb.append("updateCount:");
      sb.append(this.updateCount);
      first = false;
    }
    if (isSetBatchUpdateCounts()) {
      if (!first) sb.append(", ");
      sb.append("batchUpdateCounts:");
      if (this.batchUpdateCounts == null) {
        sb.append("null");
      } else {
        sb.append(this.batchUpdateCounts);
      }
      first = false;
    }
    if (isSetGeneratedKeys()) {
      if (!first) sb.append(", ");
      sb.append("generatedKeys:");
      if (this.generatedKeys == null) {
        sb.append("null");
      } else {
        sb.append(this.generatedKeys);
      }
      first = false;
    }
    if (isSetNewDefaultSchema()) {
      if (!first) sb.append(", ");
      sb.append("newDefaultSchema:");
      if (this.newDefaultSchema == null) {
        sb.append("null");
      } else {
        sb.append(this.newDefaultSchema);
      }
      first = false;
    }
    if (isSetWarnings()) {
      if (!first) sb.append(", ");
      sb.append("warnings:");
      if (this.warnings == null) {
        sb.append("null");
      } else {
        sb.append(this.warnings);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (generatedKeys != null) {
      generatedKeys.validate();
    }
    if (warnings != null) {
      warnings.validate();
    }
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class UpdateResultStandardSchemeFactory implements SchemeFactory {
    public UpdateResultStandardScheme getScheme() {
      return new UpdateResultStandardScheme();
    }
  }

  private static class UpdateResultStandardScheme extends StandardScheme<UpdateResult> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, UpdateResult struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // UPDATE_COUNT
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.updateCount = iprot.readI32();
              struct.setUpdateCountIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // BATCH_UPDATE_COUNTS
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list236 = iprot.readListBegin();
                struct.batchUpdateCounts = new ArrayList<Integer>(_list236.size);
                int _elem237;
                for (int _i238 = 0; _i238 < _list236.size; ++_i238)
                {
                  _elem237 = iprot.readI32();
                  struct.batchUpdateCounts.add(_elem237);
                }
                iprot.readListEnd();
              }
              struct.setBatchUpdateCountsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // GENERATED_KEYS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.generatedKeys = new RowSet();
              struct.generatedKeys.read(iprot);
              struct.setGeneratedKeysIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // NEW_DEFAULT_SCHEMA
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.newDefaultSchema = iprot.readString();
              struct.setNewDefaultSchemaIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 5: // WARNINGS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.warnings = new SnappyExceptionData();
              struct.warnings.read(iprot);
              struct.setWarningsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, UpdateResult struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.isSetUpdateCount()) {
        oprot.writeFieldBegin(UPDATE_COUNT_FIELD_DESC);
        oprot.writeI32(struct.updateCount);
        oprot.writeFieldEnd();
      }
      if (struct.batchUpdateCounts != null) {
        if (struct.isSetBatchUpdateCounts()) {
          oprot.writeFieldBegin(BATCH_UPDATE_COUNTS_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.I32, struct.batchUpdateCounts.size()));
            for (int _iter239 : struct.batchUpdateCounts)
            {
              oprot.writeI32(_iter239);
            }
            oprot.writeListEnd();
          }
          oprot.writeFieldEnd();
        }
      }
      if (struct.generatedKeys != null) {
        if (struct.isSetGeneratedKeys()) {
          oprot.writeFieldBegin(GENERATED_KEYS_FIELD_DESC);
          struct.generatedKeys.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      if (struct.newDefaultSchema != null) {
        if (struct.isSetNewDefaultSchema()) {
          oprot.writeFieldBegin(NEW_DEFAULT_SCHEMA_FIELD_DESC);
          oprot.writeString(struct.newDefaultSchema);
          oprot.writeFieldEnd();
        }
      }
      if (struct.warnings != null) {
        if (struct.isSetWarnings()) {
          oprot.writeFieldBegin(WARNINGS_FIELD_DESC);
          struct.warnings.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class UpdateResultTupleSchemeFactory implements SchemeFactory {
    public UpdateResultTupleScheme getScheme() {
      return new UpdateResultTupleScheme();
    }
  }

  private static class UpdateResultTupleScheme extends TupleScheme<UpdateResult> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, UpdateResult struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.isSetUpdateCount()) {
        optionals.set(0);
      }
      if (struct.isSetBatchUpdateCounts()) {
        optionals.set(1);
      }
      if (struct.isSetGeneratedKeys()) {
        optionals.set(2);
      }
      if (struct.isSetNewDefaultSchema()) {
        optionals.set(3);
      }
      if (struct.isSetWarnings()) {
        optionals.set(4);
      }
      oprot.writeBitSet(optionals, 5);
      if (struct.isSetUpdateCount()) {
        oprot.writeI32(struct.updateCount);
      }
      if (struct.isSetBatchUpdateCounts()) {
        {
          oprot.writeI32(struct.batchUpdateCounts.size());
          for (int _iter240 : struct.batchUpdateCounts)
          {
            oprot.writeI32(_iter240);
          }
        }
      }
      if (struct.isSetGeneratedKeys()) {
        struct.generatedKeys.write(oprot);
      }
      if (struct.isSetNewDefaultSchema()) {
        oprot.writeString(struct.newDefaultSchema);
      }
      if (struct.isSetWarnings()) {
        struct.warnings.write(oprot);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, UpdateResult struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(5);
      if (incoming.get(0)) {
        struct.updateCount = iprot.readI32();
        struct.setUpdateCountIsSet(true);
      }
      if (incoming.get(1)) {
        {
          org.apache.thrift.protocol.TList _list241 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.I32, iprot.readI32());
          struct.batchUpdateCounts = new ArrayList<Integer>(_list241.size);
          int _elem242;
          for (int _i243 = 0; _i243 < _list241.size; ++_i243)
          {
            _elem242 = iprot.readI32();
            struct.batchUpdateCounts.add(_elem242);
          }
        }
        struct.setBatchUpdateCountsIsSet(true);
      }
      if (incoming.get(2)) {
        struct.generatedKeys = new RowSet();
        struct.generatedKeys.read(iprot);
        struct.setGeneratedKeysIsSet(true);
      }
      if (incoming.get(3)) {
        struct.newDefaultSchema = iprot.readString();
        struct.setNewDefaultSchemaIsSet(true);
      }
      if (incoming.get(4)) {
        struct.warnings = new SnappyExceptionData();
        struct.warnings.read(iprot);
        struct.setWarningsIsSet(true);
      }
    }
  }

}

