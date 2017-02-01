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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-02-01")
public class Decimal implements org.apache.thrift.TBase<Decimal, Decimal._Fields>, java.io.Serializable, Cloneable, Comparable<Decimal> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("Decimal");

  private static final org.apache.thrift.protocol.TField SIGNUM_FIELD_DESC = new org.apache.thrift.protocol.TField("signum", org.apache.thrift.protocol.TType.BYTE, (short)1);
  private static final org.apache.thrift.protocol.TField SCALE_FIELD_DESC = new org.apache.thrift.protocol.TField("scale", org.apache.thrift.protocol.TType.I32, (short)2);
  private static final org.apache.thrift.protocol.TField MAGNITUDE_FIELD_DESC = new org.apache.thrift.protocol.TField("magnitude", org.apache.thrift.protocol.TType.STRING, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new DecimalStandardSchemeFactory());
    schemes.put(TupleScheme.class, new DecimalTupleSchemeFactory());
  }

  public byte signum; // required
  public int scale; // required
  public ByteBuffer magnitude; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    SIGNUM((short)1, "signum"),
    SCALE((short)2, "scale"),
    MAGNITUDE((short)3, "magnitude");

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
        case 1: // SIGNUM
          return SIGNUM;
        case 2: // SCALE
          return SCALE;
        case 3: // MAGNITUDE
          return MAGNITUDE;
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
  private static final int __SIGNUM_ISSET_ID = 0;
  private static final int __SCALE_ISSET_ID = 1;
  private byte __isset_bitfield = 0;
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.SIGNUM, new org.apache.thrift.meta_data.FieldMetaData("signum", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.BYTE)));
    tmpMap.put(_Fields.SCALE, new org.apache.thrift.meta_data.FieldMetaData("scale", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.MAGNITUDE, new org.apache.thrift.meta_data.FieldMetaData("magnitude", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(Decimal.class, metaDataMap);
  }

  public Decimal() {
  }

  public Decimal(
    byte signum,
    int scale,
    ByteBuffer magnitude)
  {
    this();
    this.signum = signum;
    setSignumIsSet(true);
    this.scale = scale;
    setScaleIsSet(true);
    this.magnitude = org.apache.thrift.TBaseHelper.copyBinary(magnitude);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public Decimal(Decimal other) {
    __isset_bitfield = other.__isset_bitfield;
    this.signum = other.signum;
    this.scale = other.scale;
    if (other.isSetMagnitude()) {
      this.magnitude = org.apache.thrift.TBaseHelper.copyBinary(other.magnitude);
    }
  }

  public Decimal deepCopy() {
    return new Decimal(this);
  }

  @Override
  public void clear() {
    setSignumIsSet(false);
    this.signum = 0;
    setScaleIsSet(false);
    this.scale = 0;
    this.magnitude = null;
  }

  public byte getSignum() {
    return this.signum;
  }

  public Decimal setSignum(byte signum) {
    this.signum = signum;
    setSignumIsSet(true);
    return this;
  }

  public void unsetSignum() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __SIGNUM_ISSET_ID);
  }

  /** Returns true if field signum is set (has been assigned a value) and false otherwise */
  public boolean isSetSignum() {
    return EncodingUtils.testBit(__isset_bitfield, __SIGNUM_ISSET_ID);
  }

  public void setSignumIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __SIGNUM_ISSET_ID, value);
  }

  public int getScale() {
    return this.scale;
  }

  public Decimal setScale(int scale) {
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

  public byte[] getMagnitude() {
    setMagnitude(org.apache.thrift.TBaseHelper.rightSize(magnitude));
    return magnitude == null ? null : magnitude.array();
  }

  public ByteBuffer bufferForMagnitude() {
    return org.apache.thrift.TBaseHelper.copyBinary(magnitude);
  }

  public Decimal setMagnitude(byte[] magnitude) {
    this.magnitude = magnitude == null ? (ByteBuffer)null : ByteBuffer.wrap(Arrays.copyOf(magnitude, magnitude.length));
    return this;
  }

  public Decimal setMagnitude(ByteBuffer magnitude) {
    this.magnitude = org.apache.thrift.TBaseHelper.copyBinary(magnitude);
    return this;
  }

  public void unsetMagnitude() {
    this.magnitude = null;
  }

  /** Returns true if field magnitude is set (has been assigned a value) and false otherwise */
  public boolean isSetMagnitude() {
    return this.magnitude != null;
  }

  public void setMagnitudeIsSet(boolean value) {
    if (!value) {
      this.magnitude = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case SIGNUM:
      if (value == null) {
        unsetSignum();
      } else {
        setSignum((Byte)value);
      }
      break;

    case SCALE:
      if (value == null) {
        unsetScale();
      } else {
        setScale((Integer)value);
      }
      break;

    case MAGNITUDE:
      if (value == null) {
        unsetMagnitude();
      } else {
        setMagnitude((ByteBuffer)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case SIGNUM:
      return getSignum();

    case SCALE:
      return getScale();

    case MAGNITUDE:
      return getMagnitude();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case SIGNUM:
      return isSetSignum();
    case SCALE:
      return isSetScale();
    case MAGNITUDE:
      return isSetMagnitude();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof Decimal)
      return this.equals((Decimal)that);
    return false;
  }

  public boolean equals(Decimal that) {
    if (that == null)
      return false;

    boolean this_present_signum = true;
    boolean that_present_signum = true;
    if (this_present_signum || that_present_signum) {
      if (!(this_present_signum && that_present_signum))
        return false;
      if (this.signum != that.signum)
        return false;
    }

    boolean this_present_scale = true;
    boolean that_present_scale = true;
    if (this_present_scale || that_present_scale) {
      if (!(this_present_scale && that_present_scale))
        return false;
      if (this.scale != that.scale)
        return false;
    }

    boolean this_present_magnitude = true && this.isSetMagnitude();
    boolean that_present_magnitude = true && that.isSetMagnitude();
    if (this_present_magnitude || that_present_magnitude) {
      if (!(this_present_magnitude && that_present_magnitude))
        return false;
      if (!this.magnitude.equals(that.magnitude))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_signum = true;
    list.add(present_signum);
    if (present_signum)
      list.add(signum);

    boolean present_scale = true;
    list.add(present_scale);
    if (present_scale)
      list.add(scale);

    boolean present_magnitude = true && (isSetMagnitude());
    list.add(present_magnitude);
    if (present_magnitude)
      list.add(magnitude);

    return list.hashCode();
  }

  @Override
  public int compareTo(Decimal other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetSignum()).compareTo(other.isSetSignum());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetSignum()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.signum, other.signum);
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
    lastComparison = Boolean.valueOf(isSetMagnitude()).compareTo(other.isSetMagnitude());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetMagnitude()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.magnitude, other.magnitude);
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
    StringBuilder sb = new StringBuilder("Decimal(");
    boolean first = true;

    sb.append("signum:");
    sb.append(this.signum);
    first = false;
    if (!first) sb.append(", ");
    sb.append("scale:");
    sb.append(this.scale);
    first = false;
    if (!first) sb.append(", ");
    sb.append("magnitude:");
    if (this.magnitude == null) {
      sb.append("null");
    } else {
      org.apache.thrift.TBaseHelper.toString(this.magnitude, sb);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // alas, we cannot check 'signum' because it's a primitive and you chose the non-beans generator.
    // alas, we cannot check 'scale' because it's a primitive and you chose the non-beans generator.
    if (magnitude == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'magnitude' was not present! Struct: " + toString());
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

  private static class DecimalStandardSchemeFactory implements SchemeFactory {
    public DecimalStandardScheme getScheme() {
      return new DecimalStandardScheme();
    }
  }

  private static class DecimalStandardScheme extends StandardScheme<Decimal> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, Decimal struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // SIGNUM
            if (schemeField.type == org.apache.thrift.protocol.TType.BYTE) {
              struct.signum = iprot.readByte();
              struct.setSignumIsSet(true);
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
          case 3: // MAGNITUDE
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.magnitude = iprot.readBinary();
              struct.setMagnitudeIsSet(true);
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
      if (!struct.isSetSignum()) {
        throw new org.apache.thrift.protocol.TProtocolException("Required field 'signum' was not found in serialized data! Struct: " + toString());
      }
      if (!struct.isSetScale()) {
        throw new org.apache.thrift.protocol.TProtocolException("Required field 'scale' was not found in serialized data! Struct: " + toString());
      }
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, Decimal struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      oprot.writeFieldBegin(SIGNUM_FIELD_DESC);
      oprot.writeByte(struct.signum);
      oprot.writeFieldEnd();
      oprot.writeFieldBegin(SCALE_FIELD_DESC);
      oprot.writeI32(struct.scale);
      oprot.writeFieldEnd();
      if (struct.magnitude != null) {
        oprot.writeFieldBegin(MAGNITUDE_FIELD_DESC);
        oprot.writeBinary(struct.magnitude);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class DecimalTupleSchemeFactory implements SchemeFactory {
    public DecimalTupleScheme getScheme() {
      return new DecimalTupleScheme();
    }
  }

  private static class DecimalTupleScheme extends TupleScheme<Decimal> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, Decimal struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      oprot.writeByte(struct.signum);
      oprot.writeI32(struct.scale);
      oprot.writeBinary(struct.magnitude);
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, Decimal struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.signum = iprot.readByte();
      struct.setSignumIsSet(true);
      struct.scale = iprot.readI32();
      struct.setScaleIsSet(true);
      struct.magnitude = iprot.readBinary();
      struct.setMagnitudeIsSet(true);
    }
  }

}

