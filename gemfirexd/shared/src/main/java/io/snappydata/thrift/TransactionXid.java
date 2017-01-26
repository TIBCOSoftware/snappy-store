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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-01-26")
public class TransactionXid implements org.apache.thrift.TBase<TransactionXid, TransactionXid._Fields>, java.io.Serializable, Cloneable, Comparable<TransactionXid> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("TransactionXid");

  private static final org.apache.thrift.protocol.TField FORMAT_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("formatId", org.apache.thrift.protocol.TType.I32, (short)1);
  private static final org.apache.thrift.protocol.TField GLOBAL_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("globalId", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField BRANCH_QUALIFIER_FIELD_DESC = new org.apache.thrift.protocol.TField("branchQualifier", org.apache.thrift.protocol.TType.STRING, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new TransactionXidStandardSchemeFactory());
    schemes.put(TupleScheme.class, new TransactionXidTupleSchemeFactory());
  }

  public int formatId; // required
  public ByteBuffer globalId; // required
  public ByteBuffer branchQualifier; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    FORMAT_ID((short)1, "formatId"),
    GLOBAL_ID((short)2, "globalId"),
    BRANCH_QUALIFIER((short)3, "branchQualifier");

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
        case 1: // FORMAT_ID
          return FORMAT_ID;
        case 2: // GLOBAL_ID
          return GLOBAL_ID;
        case 3: // BRANCH_QUALIFIER
          return BRANCH_QUALIFIER;
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
  private static final int __FORMATID_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.FORMAT_ID, new org.apache.thrift.meta_data.FieldMetaData("formatId", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.GLOBAL_ID, new org.apache.thrift.meta_data.FieldMetaData("globalId", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    tmpMap.put(_Fields.BRANCH_QUALIFIER, new org.apache.thrift.meta_data.FieldMetaData("branchQualifier", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(TransactionXid.class, metaDataMap);
  }

  public TransactionXid() {
  }

  public TransactionXid(
    int formatId,
    ByteBuffer globalId,
    ByteBuffer branchQualifier)
  {
    this();
    this.formatId = formatId;
    setFormatIdIsSet(true);
    this.globalId = org.apache.thrift.TBaseHelper.copyBinary(globalId);
    this.branchQualifier = org.apache.thrift.TBaseHelper.copyBinary(branchQualifier);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public TransactionXid(TransactionXid other) {
    __isset_bitfield = other.__isset_bitfield;
    this.formatId = other.formatId;
    if (other.isSetGlobalId()) {
      this.globalId = org.apache.thrift.TBaseHelper.copyBinary(other.globalId);
    }
    if (other.isSetBranchQualifier()) {
      this.branchQualifier = org.apache.thrift.TBaseHelper.copyBinary(other.branchQualifier);
    }
  }

  public TransactionXid deepCopy() {
    return new TransactionXid(this);
  }

  @Override
  public void clear() {
    setFormatIdIsSet(false);
    this.formatId = 0;
    this.globalId = null;
    this.branchQualifier = null;
  }

  public int getFormatId() {
    return this.formatId;
  }

  public TransactionXid setFormatId(int formatId) {
    this.formatId = formatId;
    setFormatIdIsSet(true);
    return this;
  }

  public void unsetFormatId() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __FORMATID_ISSET_ID);
  }

  /** Returns true if field formatId is set (has been assigned a value) and false otherwise */
  public boolean isSetFormatId() {
    return EncodingUtils.testBit(__isset_bitfield, __FORMATID_ISSET_ID);
  }

  public void setFormatIdIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __FORMATID_ISSET_ID, value);
  }

  public byte[] getGlobalId() {
    setGlobalId(org.apache.thrift.TBaseHelper.rightSize(globalId));
    return globalId == null ? null : globalId.array();
  }

  public ByteBuffer bufferForGlobalId() {
    return org.apache.thrift.TBaseHelper.copyBinary(globalId);
  }

  public TransactionXid setGlobalId(byte[] globalId) {
    this.globalId = globalId == null ? (ByteBuffer)null : ByteBuffer.wrap(Arrays.copyOf(globalId, globalId.length));
    return this;
  }

  public TransactionXid setGlobalId(ByteBuffer globalId) {
    this.globalId = org.apache.thrift.TBaseHelper.copyBinary(globalId);
    return this;
  }

  public void unsetGlobalId() {
    this.globalId = null;
  }

  /** Returns true if field globalId is set (has been assigned a value) and false otherwise */
  public boolean isSetGlobalId() {
    return this.globalId != null;
  }

  public void setGlobalIdIsSet(boolean value) {
    if (!value) {
      this.globalId = null;
    }
  }

  public byte[] getBranchQualifier() {
    setBranchQualifier(org.apache.thrift.TBaseHelper.rightSize(branchQualifier));
    return branchQualifier == null ? null : branchQualifier.array();
  }

  public ByteBuffer bufferForBranchQualifier() {
    return org.apache.thrift.TBaseHelper.copyBinary(branchQualifier);
  }

  public TransactionXid setBranchQualifier(byte[] branchQualifier) {
    this.branchQualifier = branchQualifier == null ? (ByteBuffer)null : ByteBuffer.wrap(Arrays.copyOf(branchQualifier, branchQualifier.length));
    return this;
  }

  public TransactionXid setBranchQualifier(ByteBuffer branchQualifier) {
    this.branchQualifier = org.apache.thrift.TBaseHelper.copyBinary(branchQualifier);
    return this;
  }

  public void unsetBranchQualifier() {
    this.branchQualifier = null;
  }

  /** Returns true if field branchQualifier is set (has been assigned a value) and false otherwise */
  public boolean isSetBranchQualifier() {
    return this.branchQualifier != null;
  }

  public void setBranchQualifierIsSet(boolean value) {
    if (!value) {
      this.branchQualifier = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case FORMAT_ID:
      if (value == null) {
        unsetFormatId();
      } else {
        setFormatId((Integer)value);
      }
      break;

    case GLOBAL_ID:
      if (value == null) {
        unsetGlobalId();
      } else {
        setGlobalId((ByteBuffer)value);
      }
      break;

    case BRANCH_QUALIFIER:
      if (value == null) {
        unsetBranchQualifier();
      } else {
        setBranchQualifier((ByteBuffer)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case FORMAT_ID:
      return getFormatId();

    case GLOBAL_ID:
      return getGlobalId();

    case BRANCH_QUALIFIER:
      return getBranchQualifier();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case FORMAT_ID:
      return isSetFormatId();
    case GLOBAL_ID:
      return isSetGlobalId();
    case BRANCH_QUALIFIER:
      return isSetBranchQualifier();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof TransactionXid)
      return this.equals((TransactionXid)that);
    return false;
  }

  public boolean equals(TransactionXid that) {
    if (that == null)
      return false;

    boolean this_present_formatId = true;
    boolean that_present_formatId = true;
    if (this_present_formatId || that_present_formatId) {
      if (!(this_present_formatId && that_present_formatId))
        return false;
      if (this.formatId != that.formatId)
        return false;
    }

    boolean this_present_globalId = true && this.isSetGlobalId();
    boolean that_present_globalId = true && that.isSetGlobalId();
    if (this_present_globalId || that_present_globalId) {
      if (!(this_present_globalId && that_present_globalId))
        return false;
      if (!this.globalId.equals(that.globalId))
        return false;
    }

    boolean this_present_branchQualifier = true && this.isSetBranchQualifier();
    boolean that_present_branchQualifier = true && that.isSetBranchQualifier();
    if (this_present_branchQualifier || that_present_branchQualifier) {
      if (!(this_present_branchQualifier && that_present_branchQualifier))
        return false;
      if (!this.branchQualifier.equals(that.branchQualifier))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_formatId = true;
    list.add(present_formatId);
    if (present_formatId)
      list.add(formatId);

    boolean present_globalId = true && (isSetGlobalId());
    list.add(present_globalId);
    if (present_globalId)
      list.add(globalId);

    boolean present_branchQualifier = true && (isSetBranchQualifier());
    list.add(present_branchQualifier);
    if (present_branchQualifier)
      list.add(branchQualifier);

    return list.hashCode();
  }

  @Override
  public int compareTo(TransactionXid other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetFormatId()).compareTo(other.isSetFormatId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFormatId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.formatId, other.formatId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetGlobalId()).compareTo(other.isSetGlobalId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetGlobalId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.globalId, other.globalId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetBranchQualifier()).compareTo(other.isSetBranchQualifier());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBranchQualifier()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.branchQualifier, other.branchQualifier);
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
    StringBuilder sb = new StringBuilder("TransactionXid(");
    boolean first = true;

    sb.append("formatId:");
    sb.append(this.formatId);
    first = false;
    if (!first) sb.append(", ");
    sb.append("globalId:");
    if (this.globalId == null) {
      sb.append("null");
    } else {
      org.apache.thrift.TBaseHelper.toString(this.globalId, sb);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("branchQualifier:");
    if (this.branchQualifier == null) {
      sb.append("null");
    } else {
      org.apache.thrift.TBaseHelper.toString(this.branchQualifier, sb);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // alas, we cannot check 'formatId' because it's a primitive and you chose the non-beans generator.
    if (globalId == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'globalId' was not present! Struct: " + toString());
    }
    if (branchQualifier == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'branchQualifier' was not present! Struct: " + toString());
    }
    // check for sub-struct validity
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

  private static class TransactionXidStandardSchemeFactory implements SchemeFactory {
    public TransactionXidStandardScheme getScheme() {
      return new TransactionXidStandardScheme();
    }
  }

  private static class TransactionXidStandardScheme extends StandardScheme<TransactionXid> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, TransactionXid struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // FORMAT_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.formatId = iprot.readI32();
              struct.setFormatIdIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // GLOBAL_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.globalId = iprot.readBinary();
              struct.setGlobalIdIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // BRANCH_QUALIFIER
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.branchQualifier = iprot.readBinary();
              struct.setBranchQualifierIsSet(true);
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
      if (!struct.isSetFormatId()) {
        throw new org.apache.thrift.protocol.TProtocolException("Required field 'formatId' was not found in serialized data! Struct: " + toString());
      }
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, TransactionXid struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      oprot.writeFieldBegin(FORMAT_ID_FIELD_DESC);
      oprot.writeI32(struct.formatId);
      oprot.writeFieldEnd();
      if (struct.globalId != null) {
        oprot.writeFieldBegin(GLOBAL_ID_FIELD_DESC);
        oprot.writeBinary(struct.globalId);
        oprot.writeFieldEnd();
      }
      if (struct.branchQualifier != null) {
        oprot.writeFieldBegin(BRANCH_QUALIFIER_FIELD_DESC);
        oprot.writeBinary(struct.branchQualifier);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class TransactionXidTupleSchemeFactory implements SchemeFactory {
    public TransactionXidTupleScheme getScheme() {
      return new TransactionXidTupleScheme();
    }
  }

  private static class TransactionXidTupleScheme extends TupleScheme<TransactionXid> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, TransactionXid struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      oprot.writeI32(struct.formatId);
      oprot.writeBinary(struct.globalId);
      oprot.writeBinary(struct.branchQualifier);
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, TransactionXid struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.formatId = iprot.readI32();
      struct.setFormatIdIsSet(true);
      struct.globalId = iprot.readBinary();
      struct.setGlobalIdIsSet(true);
      struct.branchQualifier = iprot.readBinary();
      struct.setBranchQualifierIsSet(true);
    }
  }

}

