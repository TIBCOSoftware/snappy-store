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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2018-11-28")
public class SnappyException extends TException implements org.apache.thrift.TBase<SnappyException, SnappyException._Fields>, java.io.Serializable, Cloneable, Comparable<SnappyException> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("SnappyException");

  private static final org.apache.thrift.protocol.TField EXCEPTION_DATA_FIELD_DESC = new org.apache.thrift.protocol.TField("exceptionData", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField SERVER_INFO_FIELD_DESC = new org.apache.thrift.protocol.TField("serverInfo", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField NEXT_EXCEPTIONS_FIELD_DESC = new org.apache.thrift.protocol.TField("nextExceptions", org.apache.thrift.protocol.TType.LIST, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new SnappyExceptionStandardSchemeFactory());
    schemes.put(TupleScheme.class, new SnappyExceptionTupleSchemeFactory());
  }

  public SnappyExceptionData exceptionData; // required
  public String serverInfo; // required
  public List<SnappyExceptionData> nextExceptions; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    EXCEPTION_DATA((short)1, "exceptionData"),
    SERVER_INFO((short)2, "serverInfo"),
    NEXT_EXCEPTIONS((short)3, "nextExceptions");

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
        case 1: // EXCEPTION_DATA
          return EXCEPTION_DATA;
        case 2: // SERVER_INFO
          return SERVER_INFO;
        case 3: // NEXT_EXCEPTIONS
          return NEXT_EXCEPTIONS;
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
  private static final _Fields optionals[] = {_Fields.NEXT_EXCEPTIONS};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.EXCEPTION_DATA, new org.apache.thrift.meta_data.FieldMetaData("exceptionData", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, SnappyExceptionData.class)));
    tmpMap.put(_Fields.SERVER_INFO, new org.apache.thrift.meta_data.FieldMetaData("serverInfo", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.NEXT_EXCEPTIONS, new org.apache.thrift.meta_data.FieldMetaData("nextExceptions", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, SnappyExceptionData.class))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(SnappyException.class, metaDataMap);
  }

  public SnappyException() {
  }

  public SnappyException(
    SnappyExceptionData exceptionData,
    String serverInfo)
  {
    this();
    this.exceptionData = exceptionData;
    this.serverInfo = serverInfo;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public SnappyException(SnappyException other) {
    if (other.isSetExceptionData()) {
      this.exceptionData = new SnappyExceptionData(other.exceptionData);
    }
    if (other.isSetServerInfo()) {
      this.serverInfo = other.serverInfo;
    }
    if (other.isSetNextExceptions()) {
      List<SnappyExceptionData> __this__nextExceptions = new ArrayList<SnappyExceptionData>(other.nextExceptions.size());
      for (SnappyExceptionData other_element : other.nextExceptions) {
        __this__nextExceptions.add(new SnappyExceptionData(other_element));
      }
      this.nextExceptions = __this__nextExceptions;
    }
  }

  public SnappyException deepCopy() {
    return new SnappyException(this);
  }

  @Override
  public void clear() {
    this.exceptionData = null;
    this.serverInfo = null;
    this.nextExceptions = null;
  }

  public SnappyExceptionData getExceptionData() {
    return this.exceptionData;
  }

  public SnappyException setExceptionData(SnappyExceptionData exceptionData) {
    this.exceptionData = exceptionData;
    return this;
  }

  public void unsetExceptionData() {
    this.exceptionData = null;
  }

  /** Returns true if field exceptionData is set (has been assigned a value) and false otherwise */
  public boolean isSetExceptionData() {
    return this.exceptionData != null;
  }

  public void setExceptionDataIsSet(boolean value) {
    if (!value) {
      this.exceptionData = null;
    }
  }

  public String getServerInfo() {
    return this.serverInfo;
  }

  public SnappyException setServerInfo(String serverInfo) {
    this.serverInfo = serverInfo;
    return this;
  }

  public void unsetServerInfo() {
    this.serverInfo = null;
  }

  /** Returns true if field serverInfo is set (has been assigned a value) and false otherwise */
  public boolean isSetServerInfo() {
    return this.serverInfo != null;
  }

  public void setServerInfoIsSet(boolean value) {
    if (!value) {
      this.serverInfo = null;
    }
  }

  public int getNextExceptionsSize() {
    return (this.nextExceptions == null) ? 0 : this.nextExceptions.size();
  }

  public java.util.Iterator<SnappyExceptionData> getNextExceptionsIterator() {
    return (this.nextExceptions == null) ? null : this.nextExceptions.iterator();
  }

  public void addToNextExceptions(SnappyExceptionData elem) {
    if (this.nextExceptions == null) {
      this.nextExceptions = new ArrayList<SnappyExceptionData>();
    }
    this.nextExceptions.add(elem);
  }

  public List<SnappyExceptionData> getNextExceptions() {
    return this.nextExceptions;
  }

  public SnappyException setNextExceptions(List<SnappyExceptionData> nextExceptions) {
    this.nextExceptions = nextExceptions;
    return this;
  }

  public void unsetNextExceptions() {
    this.nextExceptions = null;
  }

  /** Returns true if field nextExceptions is set (has been assigned a value) and false otherwise */
  public boolean isSetNextExceptions() {
    return this.nextExceptions != null;
  }

  public void setNextExceptionsIsSet(boolean value) {
    if (!value) {
      this.nextExceptions = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case EXCEPTION_DATA:
      if (value == null) {
        unsetExceptionData();
      } else {
        setExceptionData((SnappyExceptionData)value);
      }
      break;

    case SERVER_INFO:
      if (value == null) {
        unsetServerInfo();
      } else {
        setServerInfo((String)value);
      }
      break;

    case NEXT_EXCEPTIONS:
      if (value == null) {
        unsetNextExceptions();
      } else {
        setNextExceptions((List<SnappyExceptionData>)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case EXCEPTION_DATA:
      return getExceptionData();

    case SERVER_INFO:
      return getServerInfo();

    case NEXT_EXCEPTIONS:
      return getNextExceptions();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case EXCEPTION_DATA:
      return isSetExceptionData();
    case SERVER_INFO:
      return isSetServerInfo();
    case NEXT_EXCEPTIONS:
      return isSetNextExceptions();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof SnappyException)
      return this.equals((SnappyException)that);
    return false;
  }

  public boolean equals(SnappyException that) {
    if (that == null)
      return false;

    boolean this_present_exceptionData = true && this.isSetExceptionData();
    boolean that_present_exceptionData = true && that.isSetExceptionData();
    if (this_present_exceptionData || that_present_exceptionData) {
      if (!(this_present_exceptionData && that_present_exceptionData))
        return false;
      if (!this.exceptionData.equals(that.exceptionData))
        return false;
    }

    boolean this_present_serverInfo = true && this.isSetServerInfo();
    boolean that_present_serverInfo = true && that.isSetServerInfo();
    if (this_present_serverInfo || that_present_serverInfo) {
      if (!(this_present_serverInfo && that_present_serverInfo))
        return false;
      if (!this.serverInfo.equals(that.serverInfo))
        return false;
    }

    boolean this_present_nextExceptions = true && this.isSetNextExceptions();
    boolean that_present_nextExceptions = true && that.isSetNextExceptions();
    if (this_present_nextExceptions || that_present_nextExceptions) {
      if (!(this_present_nextExceptions && that_present_nextExceptions))
        return false;
      if (!this.nextExceptions.equals(that.nextExceptions))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_exceptionData = true && (isSetExceptionData());
    list.add(present_exceptionData);
    if (present_exceptionData)
      list.add(exceptionData);

    boolean present_serverInfo = true && (isSetServerInfo());
    list.add(present_serverInfo);
    if (present_serverInfo)
      list.add(serverInfo);

    boolean present_nextExceptions = true && (isSetNextExceptions());
    list.add(present_nextExceptions);
    if (present_nextExceptions)
      list.add(nextExceptions);

    return list.hashCode();
  }

  @Override
  public int compareTo(SnappyException other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetExceptionData()).compareTo(other.isSetExceptionData());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetExceptionData()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.exceptionData, other.exceptionData);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetServerInfo()).compareTo(other.isSetServerInfo());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetServerInfo()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.serverInfo, other.serverInfo);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetNextExceptions()).compareTo(other.isSetNextExceptions());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetNextExceptions()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.nextExceptions, other.nextExceptions);
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
    StringBuilder sb = new StringBuilder("SnappyException(");
    boolean first = true;

    sb.append("exceptionData:");
    if (this.exceptionData == null) {
      sb.append("null");
    } else {
      sb.append(this.exceptionData);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("serverInfo:");
    if (this.serverInfo == null) {
      sb.append("null");
    } else {
      sb.append(this.serverInfo);
    }
    first = false;
    if (isSetNextExceptions()) {
      if (!first) sb.append(", ");
      sb.append("nextExceptions:");
      if (this.nextExceptions == null) {
        sb.append("null");
      } else {
        sb.append(this.nextExceptions);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (exceptionData == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'exceptionData' was not present! Struct: " + toString());
    }
    if (serverInfo == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'serverInfo' was not present! Struct: " + toString());
    }
    // check for sub-struct validity
    if (exceptionData != null) {
      exceptionData.validate();
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
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class SnappyExceptionStandardSchemeFactory implements SchemeFactory {
    public SnappyExceptionStandardScheme getScheme() {
      return new SnappyExceptionStandardScheme();
    }
  }

  private static class SnappyExceptionStandardScheme extends StandardScheme<SnappyException> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, SnappyException struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // EXCEPTION_DATA
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.exceptionData = new SnappyExceptionData();
              struct.exceptionData.read(iprot);
              struct.setExceptionDataIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // SERVER_INFO
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.serverInfo = iprot.readString();
              struct.setServerInfoIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // NEXT_EXCEPTIONS
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list120 = iprot.readListBegin();
                struct.nextExceptions = new ArrayList<SnappyExceptionData>(_list120.size);
                SnappyExceptionData _elem121;
                for (int _i122 = 0; _i122 < _list120.size; ++_i122)
                {
                  _elem121 = new SnappyExceptionData();
                  _elem121.read(iprot);
                  struct.nextExceptions.add(_elem121);
                }
                iprot.readListEnd();
              }
              struct.setNextExceptionsIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, SnappyException struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.exceptionData != null) {
        oprot.writeFieldBegin(EXCEPTION_DATA_FIELD_DESC);
        struct.exceptionData.write(oprot);
        oprot.writeFieldEnd();
      }
      if (struct.serverInfo != null) {
        oprot.writeFieldBegin(SERVER_INFO_FIELD_DESC);
        oprot.writeString(struct.serverInfo);
        oprot.writeFieldEnd();
      }
      if (struct.nextExceptions != null) {
        if (struct.isSetNextExceptions()) {
          oprot.writeFieldBegin(NEXT_EXCEPTIONS_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, struct.nextExceptions.size()));
            for (SnappyExceptionData _iter123 : struct.nextExceptions)
            {
              _iter123.write(oprot);
            }
            oprot.writeListEnd();
          }
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class SnappyExceptionTupleSchemeFactory implements SchemeFactory {
    public SnappyExceptionTupleScheme getScheme() {
      return new SnappyExceptionTupleScheme();
    }
  }

  private static class SnappyExceptionTupleScheme extends TupleScheme<SnappyException> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, SnappyException struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      struct.exceptionData.write(oprot);
      oprot.writeString(struct.serverInfo);
      BitSet optionals = new BitSet();
      if (struct.isSetNextExceptions()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetNextExceptions()) {
        {
          oprot.writeI32(struct.nextExceptions.size());
          for (SnappyExceptionData _iter124 : struct.nextExceptions)
          {
            _iter124.write(oprot);
          }
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, SnappyException struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.exceptionData = new SnappyExceptionData();
      struct.exceptionData.read(iprot);
      struct.setExceptionDataIsSet(true);
      struct.serverInfo = iprot.readString();
      struct.setServerInfoIsSet(true);
      BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        {
          org.apache.thrift.protocol.TList _list125 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, iprot.readI32());
          struct.nextExceptions = new ArrayList<SnappyExceptionData>(_list125.size);
          SnappyExceptionData _elem126;
          for (int _i127 = 0; _i127 < _list125.size; ++_i127)
          {
            _elem126 = new SnappyExceptionData();
            _elem126.read(iprot);
            struct.nextExceptions.add(_elem126);
          }
        }
        struct.setNextExceptionsIsSet(true);
      }
    }
  }

}

