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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-03-10")
public class OutputParameter implements org.apache.thrift.TBase<OutputParameter, OutputParameter._Fields>, java.io.Serializable, Cloneable, Comparable<OutputParameter> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("OutputParameter");

  private static final org.apache.thrift.protocol.TField TYPE_FIELD_DESC = new org.apache.thrift.protocol.TField("type", org.apache.thrift.protocol.TType.I32, (short)1);
  private static final org.apache.thrift.protocol.TField SCALE_FIELD_DESC = new org.apache.thrift.protocol.TField("scale", org.apache.thrift.protocol.TType.I32, (short)2);
  private static final org.apache.thrift.protocol.TField TYPE_NAME_FIELD_DESC = new org.apache.thrift.protocol.TField("typeName", org.apache.thrift.protocol.TType.STRING, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new OutputParameterStandardSchemeFactory());
    schemes.put(TupleScheme.class, new OutputParameterTupleSchemeFactory());
  }

  /**
   * 
   * @see SnappyType
   */
  public SnappyType type; // required
  public int scale; // optional
  public String typeName; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    /**
     * 
     * @see SnappyType
     */
    TYPE((short)1, "type"),
    SCALE((short)2, "scale"),
    TYPE_NAME((short)3, "typeName");

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
        case 1: // TYPE
          return TYPE;
        case 2: // SCALE
          return SCALE;
        case 3: // TYPE_NAME
          return TYPE_NAME;
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
  private static final int __SCALE_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.SCALE,_Fields.TYPE_NAME};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.TYPE, new org.apache.thrift.meta_data.FieldMetaData("type", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.EnumMetaData(org.apache.thrift.protocol.TType.ENUM, SnappyType.class)));
    tmpMap.put(_Fields.SCALE, new org.apache.thrift.meta_data.FieldMetaData("scale", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.TYPE_NAME, new org.apache.thrift.meta_data.FieldMetaData("typeName", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(OutputParameter.class, metaDataMap);
  }

  public OutputParameter() {
  }

  public OutputParameter(
    SnappyType type)
  {
    this();
    this.type = type;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public OutputParameter(OutputParameter other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetType()) {
      this.type = other.type;
    }
    this.scale = other.scale;
    if (other.isSetTypeName()) {
      this.typeName = other.typeName;
    }
  }

  public OutputParameter deepCopy() {
    return new OutputParameter(this);
  }

  @Override
  public void clear() {
    this.type = null;
    setScaleIsSet(false);
    this.scale = 0;
    this.typeName = null;
  }

  /**
   * 
   * @see SnappyType
   */
  public SnappyType getType() {
    return this.type;
  }

  /**
   * 
   * @see SnappyType
   */
  public OutputParameter setType(SnappyType type) {
    this.type = type;
    return this;
  }

  public void unsetType() {
    this.type = null;
  }

  /** Returns true if field type is set (has been assigned a value) and false otherwise */
  public boolean isSetType() {
    return this.type != null;
  }

  public void setTypeIsSet(boolean value) {
    if (!value) {
      this.type = null;
    }
  }

  public int getScale() {
    return this.scale;
  }

  public OutputParameter setScale(int scale) {
    this.scale = scale;
    setScaleIsSet(true);
    return this;
  }

  public void unsetScale() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __SCALE_ISSET_ID);
  }

  /** Returns true if field scale is set (has been assigned a value) and false otherwise */
  public boolean isSetScale() {
    return EncodingUtils.testBit(__isset_bitfield, __SCALE_ISSET_ID);
  }

  public void setScaleIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __SCALE_ISSET_ID, value);
  }

  public String getTypeName() {
    return this.typeName;
  }

  public OutputParameter setTypeName(String typeName) {
    this.typeName = typeName;
    return this;
  }

  public void unsetTypeName() {
    this.typeName = null;
  }

  /** Returns true if field typeName is set (has been assigned a value) and false otherwise */
  public boolean isSetTypeName() {
    return this.typeName != null;
  }

  public void setTypeNameIsSet(boolean value) {
    if (!value) {
      this.typeName = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case TYPE:
      if (value == null) {
        unsetType();
      } else {
        setType((SnappyType)value);
      }
      break;

    case SCALE:
      if (value == null) {
        unsetScale();
      } else {
        setScale((Integer)value);
      }
      break;

    case TYPE_NAME:
      if (value == null) {
        unsetTypeName();
      } else {
        setTypeName((String)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case TYPE:
      return getType();

    case SCALE:
      return getScale();

    case TYPE_NAME:
      return getTypeName();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case TYPE:
      return isSetType();
    case SCALE:
      return isSetScale();
    case TYPE_NAME:
      return isSetTypeName();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof OutputParameter)
      return this.equals((OutputParameter)that);
    return false;
  }

  public boolean equals(OutputParameter that) {
    if (that == null)
      return false;

    boolean this_present_type = true && this.isSetType();
    boolean that_present_type = true && that.isSetType();
    if (this_present_type || that_present_type) {
      if (!(this_present_type && that_present_type))
        return false;
      if (!this.type.equals(that.type))
        return false;
    }

    boolean this_present_scale = true && this.isSetScale();
    boolean that_present_scale = true && that.isSetScale();
    if (this_present_scale || that_present_scale) {
      if (!(this_present_scale && that_present_scale))
        return false;
      if (this.scale != that.scale)
        return false;
    }

    boolean this_present_typeName = true && this.isSetTypeName();
    boolean that_present_typeName = true && that.isSetTypeName();
    if (this_present_typeName || that_present_typeName) {
      if (!(this_present_typeName && that_present_typeName))
        return false;
      if (!this.typeName.equals(that.typeName))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_type = true && (isSetType());
    list.add(present_type);
    if (present_type)
      list.add(type.getValue());

    boolean present_scale = true && (isSetScale());
    list.add(present_scale);
    if (present_scale)
      list.add(scale);

    boolean present_typeName = true && (isSetTypeName());
    list.add(present_typeName);
    if (present_typeName)
      list.add(typeName);

    return list.hashCode();
  }

  @Override
  public int compareTo(OutputParameter other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetType()).compareTo(other.isSetType());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetType()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.type, other.type);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetScale()).compareTo(other.isSetScale());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetScale()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.scale, other.scale);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetTypeName()).compareTo(other.isSetTypeName());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetTypeName()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.typeName, other.typeName);
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
    StringBuilder sb = new StringBuilder("OutputParameter(");
    boolean first = true;

    sb.append("type:");
    if (this.type == null) {
      sb.append("null");
    } else {
      sb.append(this.type);
    }
    first = false;
    if (isSetScale()) {
      if (!first) sb.append(", ");
      sb.append("scale:");
      sb.append(this.scale);
      first = false;
    }
    if (isSetTypeName()) {
      if (!first) sb.append(", ");
      sb.append("typeName:");
      if (this.typeName == null) {
        sb.append("null");
      } else {
        sb.append(this.typeName);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (type == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'type' was not present! Struct: " + toString());
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

  private static class OutputParameterStandardSchemeFactory implements SchemeFactory {
    public OutputParameterStandardScheme getScheme() {
      return new OutputParameterStandardScheme();
    }
  }

  private static class OutputParameterStandardScheme extends StandardScheme<OutputParameter> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, OutputParameter struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // TYPE
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.type = io.snappydata.thrift.SnappyType.findByValue(iprot.readI32());
              struct.setTypeIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // SCALE
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.scale = iprot.readI32();
              struct.setScaleIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // TYPE_NAME
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.typeName = iprot.readString();
              struct.setTypeNameIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, OutputParameter struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.type != null) {
        oprot.writeFieldBegin(TYPE_FIELD_DESC);
        oprot.writeI32(struct.type.getValue());
        oprot.writeFieldEnd();
      }
      if (struct.isSetScale()) {
        oprot.writeFieldBegin(SCALE_FIELD_DESC);
        oprot.writeI32(struct.scale);
        oprot.writeFieldEnd();
      }
      if (struct.typeName != null) {
        if (struct.isSetTypeName()) {
          oprot.writeFieldBegin(TYPE_NAME_FIELD_DESC);
          oprot.writeString(struct.typeName);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class OutputParameterTupleSchemeFactory implements SchemeFactory {
    public OutputParameterTupleScheme getScheme() {
      return new OutputParameterTupleScheme();
    }
  }

  private static class OutputParameterTupleScheme extends TupleScheme<OutputParameter> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, OutputParameter struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      oprot.writeI32(struct.type.getValue());
      BitSet optionals = new BitSet();
      if (struct.isSetScale()) {
        optionals.set(0);
      }
      if (struct.isSetTypeName()) {
        optionals.set(1);
      }
      oprot.writeBitSet(optionals, 2);
      if (struct.isSetScale()) {
        oprot.writeI32(struct.scale);
      }
      if (struct.isSetTypeName()) {
        oprot.writeString(struct.typeName);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, OutputParameter struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.type = io.snappydata.thrift.SnappyType.findByValue(iprot.readI32());
      struct.setTypeIsSet(true);
      BitSet incoming = iprot.readBitSet(2);
      if (incoming.get(0)) {
        struct.scale = iprot.readI32();
        struct.setScaleIsSet(true);
      }
      if (incoming.get(1)) {
        struct.typeName = iprot.readString();
        struct.setTypeNameIsSet(true);
      }
    }
  }

}

