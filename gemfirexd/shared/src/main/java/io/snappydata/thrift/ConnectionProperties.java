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
public class ConnectionProperties implements org.apache.thrift.TBase<ConnectionProperties, ConnectionProperties._Fields>, java.io.Serializable, Cloneable, Comparable<ConnectionProperties> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("ConnectionProperties");

  private static final org.apache.thrift.protocol.TField CONN_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("connId", org.apache.thrift.protocol.TType.I64, (short)1);
  private static final org.apache.thrift.protocol.TField CLIENT_HOST_NAME_FIELD_DESC = new org.apache.thrift.protocol.TField("clientHostName", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField CLIENT_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("clientID", org.apache.thrift.protocol.TType.STRING, (short)3);
  private static final org.apache.thrift.protocol.TField USER_NAME_FIELD_DESC = new org.apache.thrift.protocol.TField("userName", org.apache.thrift.protocol.TType.STRING, (short)4);
  private static final org.apache.thrift.protocol.TField TOKEN_FIELD_DESC = new org.apache.thrift.protocol.TField("token", org.apache.thrift.protocol.TType.STRING, (short)5);
  private static final org.apache.thrift.protocol.TField DEFAULT_SCHEMA_FIELD_DESC = new org.apache.thrift.protocol.TField("defaultSchema", org.apache.thrift.protocol.TType.STRING, (short)6);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new ConnectionPropertiesStandardSchemeFactory());
    schemes.put(TupleScheme.class, new ConnectionPropertiesTupleSchemeFactory());
  }

  public long connId; // required
  public String clientHostName; // required
  public String clientID; // required
  public String userName; // optional
  public ByteBuffer token; // optional
  public String defaultSchema; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    CONN_ID((short)1, "connId"),
    CLIENT_HOST_NAME((short)2, "clientHostName"),
    CLIENT_ID((short)3, "clientID"),
    USER_NAME((short)4, "userName"),
    TOKEN((short)5, "token"),
    DEFAULT_SCHEMA((short)6, "defaultSchema");

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
        case 1: // CONN_ID
          return CONN_ID;
        case 2: // CLIENT_HOST_NAME
          return CLIENT_HOST_NAME;
        case 3: // CLIENT_ID
          return CLIENT_ID;
        case 4: // USER_NAME
          return USER_NAME;
        case 5: // TOKEN
          return TOKEN;
        case 6: // DEFAULT_SCHEMA
          return DEFAULT_SCHEMA;
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
  private static final int __CONNID_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.USER_NAME,_Fields.TOKEN,_Fields.DEFAULT_SCHEMA};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.CONN_ID, new org.apache.thrift.meta_data.FieldMetaData("connId", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(_Fields.CLIENT_HOST_NAME, new org.apache.thrift.meta_data.FieldMetaData("clientHostName", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.CLIENT_ID, new org.apache.thrift.meta_data.FieldMetaData("clientID", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.USER_NAME, new org.apache.thrift.meta_data.FieldMetaData("userName", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.TOKEN, new org.apache.thrift.meta_data.FieldMetaData("token", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    tmpMap.put(_Fields.DEFAULT_SCHEMA, new org.apache.thrift.meta_data.FieldMetaData("defaultSchema", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(ConnectionProperties.class, metaDataMap);
  }

  public ConnectionProperties() {
  }

  public ConnectionProperties(
    long connId,
    String clientHostName,
    String clientID)
  {
    this();
    this.connId = connId;
    setConnIdIsSet(true);
    this.clientHostName = clientHostName;
    this.clientID = clientID;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public ConnectionProperties(ConnectionProperties other) {
    __isset_bitfield = other.__isset_bitfield;
    this.connId = other.connId;
    if (other.isSetClientHostName()) {
      this.clientHostName = other.clientHostName;
    }
    if (other.isSetClientID()) {
      this.clientID = other.clientID;
    }
    if (other.isSetUserName()) {
      this.userName = other.userName;
    }
    if (other.isSetToken()) {
      this.token = org.apache.thrift.TBaseHelper.copyBinary(other.token);
    }
    if (other.isSetDefaultSchema()) {
      this.defaultSchema = other.defaultSchema;
    }
  }

  public ConnectionProperties deepCopy() {
    return new ConnectionProperties(this);
  }

  @Override
  public void clear() {
    setConnIdIsSet(false);
    this.connId = 0;
    this.clientHostName = null;
    this.clientID = null;
    this.userName = null;
    this.token = null;
    this.defaultSchema = null;
  }

  public long getConnId() {
    return this.connId;
  }

  public ConnectionProperties setConnId(long connId) {
    this.connId = connId;
    setConnIdIsSet(true);
    return this;
  }

  public void unsetConnId() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __CONNID_ISSET_ID);
  }

  /** Returns true if field connId is set (has been assigned a value) and false otherwise */
  public boolean isSetConnId() {
    return EncodingUtils.testBit(__isset_bitfield, __CONNID_ISSET_ID);
  }

  public void setConnIdIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __CONNID_ISSET_ID, value);
  }

  public String getClientHostName() {
    return this.clientHostName;
  }

  public ConnectionProperties setClientHostName(String clientHostName) {
    this.clientHostName = clientHostName;
    return this;
  }

  public void unsetClientHostName() {
    this.clientHostName = null;
  }

  /** Returns true if field clientHostName is set (has been assigned a value) and false otherwise */
  public boolean isSetClientHostName() {
    return this.clientHostName != null;
  }

  public void setClientHostNameIsSet(boolean value) {
    if (!value) {
      this.clientHostName = null;
    }
  }

  public String getClientID() {
    return this.clientID;
  }

  public ConnectionProperties setClientID(String clientID) {
    this.clientID = clientID;
    return this;
  }

  public void unsetClientID() {
    this.clientID = null;
  }

  /** Returns true if field clientID is set (has been assigned a value) and false otherwise */
  public boolean isSetClientID() {
    return this.clientID != null;
  }

  public void setClientIDIsSet(boolean value) {
    if (!value) {
      this.clientID = null;
    }
  }

  public String getUserName() {
    return this.userName;
  }

  public ConnectionProperties setUserName(String userName) {
    this.userName = userName;
    return this;
  }

  public void unsetUserName() {
    this.userName = null;
  }

  /** Returns true if field userName is set (has been assigned a value) and false otherwise */
  public boolean isSetUserName() {
    return this.userName != null;
  }

  public void setUserNameIsSet(boolean value) {
    if (!value) {
      this.userName = null;
    }
  }

  public byte[] getToken() {
    setToken(org.apache.thrift.TBaseHelper.rightSize(token));
    return token == null ? null : token.array();
  }

  public ByteBuffer bufferForToken() {
    return org.apache.thrift.TBaseHelper.copyBinary(token);
  }

  public ConnectionProperties setToken(byte[] token) {
    this.token = token == null ? (ByteBuffer)null : ByteBuffer.wrap(Arrays.copyOf(token, token.length));
    return this;
  }

  public ConnectionProperties setToken(ByteBuffer token) {
    this.token = org.apache.thrift.TBaseHelper.copyBinary(token);
    return this;
  }

  public void unsetToken() {
    this.token = null;
  }

  /** Returns true if field token is set (has been assigned a value) and false otherwise */
  public boolean isSetToken() {
    return this.token != null;
  }

  public void setTokenIsSet(boolean value) {
    if (!value) {
      this.token = null;
    }
  }

  public String getDefaultSchema() {
    return this.defaultSchema;
  }

  public ConnectionProperties setDefaultSchema(String defaultSchema) {
    this.defaultSchema = defaultSchema;
    return this;
  }

  public void unsetDefaultSchema() {
    this.defaultSchema = null;
  }

  /** Returns true if field defaultSchema is set (has been assigned a value) and false otherwise */
  public boolean isSetDefaultSchema() {
    return this.defaultSchema != null;
  }

  public void setDefaultSchemaIsSet(boolean value) {
    if (!value) {
      this.defaultSchema = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case CONN_ID:
      if (value == null) {
        unsetConnId();
      } else {
        setConnId((Long)value);
      }
      break;

    case CLIENT_HOST_NAME:
      if (value == null) {
        unsetClientHostName();
      } else {
        setClientHostName((String)value);
      }
      break;

    case CLIENT_ID:
      if (value == null) {
        unsetClientID();
      } else {
        setClientID((String)value);
      }
      break;

    case USER_NAME:
      if (value == null) {
        unsetUserName();
      } else {
        setUserName((String)value);
      }
      break;

    case TOKEN:
      if (value == null) {
        unsetToken();
      } else {
        setToken((ByteBuffer)value);
      }
      break;

    case DEFAULT_SCHEMA:
      if (value == null) {
        unsetDefaultSchema();
      } else {
        setDefaultSchema((String)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case CONN_ID:
      return getConnId();

    case CLIENT_HOST_NAME:
      return getClientHostName();

    case CLIENT_ID:
      return getClientID();

    case USER_NAME:
      return getUserName();

    case TOKEN:
      return getToken();

    case DEFAULT_SCHEMA:
      return getDefaultSchema();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case CONN_ID:
      return isSetConnId();
    case CLIENT_HOST_NAME:
      return isSetClientHostName();
    case CLIENT_ID:
      return isSetClientID();
    case USER_NAME:
      return isSetUserName();
    case TOKEN:
      return isSetToken();
    case DEFAULT_SCHEMA:
      return isSetDefaultSchema();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof ConnectionProperties)
      return this.equals((ConnectionProperties)that);
    return false;
  }

  public boolean equals(ConnectionProperties that) {
    if (that == null)
      return false;

    boolean this_present_connId = true;
    boolean that_present_connId = true;
    if (this_present_connId || that_present_connId) {
      if (!(this_present_connId && that_present_connId))
        return false;
      if (this.connId != that.connId)
        return false;
    }

    boolean this_present_clientHostName = true && this.isSetClientHostName();
    boolean that_present_clientHostName = true && that.isSetClientHostName();
    if (this_present_clientHostName || that_present_clientHostName) {
      if (!(this_present_clientHostName && that_present_clientHostName))
        return false;
      if (!this.clientHostName.equals(that.clientHostName))
        return false;
    }

    boolean this_present_clientID = true && this.isSetClientID();
    boolean that_present_clientID = true && that.isSetClientID();
    if (this_present_clientID || that_present_clientID) {
      if (!(this_present_clientID && that_present_clientID))
        return false;
      if (!this.clientID.equals(that.clientID))
        return false;
    }

    boolean this_present_userName = true && this.isSetUserName();
    boolean that_present_userName = true && that.isSetUserName();
    if (this_present_userName || that_present_userName) {
      if (!(this_present_userName && that_present_userName))
        return false;
      if (!this.userName.equals(that.userName))
        return false;
    }

    boolean this_present_token = true && this.isSetToken();
    boolean that_present_token = true && that.isSetToken();
    if (this_present_token || that_present_token) {
      if (!(this_present_token && that_present_token))
        return false;
      if (!this.token.equals(that.token))
        return false;
    }

    boolean this_present_defaultSchema = true && this.isSetDefaultSchema();
    boolean that_present_defaultSchema = true && that.isSetDefaultSchema();
    if (this_present_defaultSchema || that_present_defaultSchema) {
      if (!(this_present_defaultSchema && that_present_defaultSchema))
        return false;
      if (!this.defaultSchema.equals(that.defaultSchema))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_connId = true;
    list.add(present_connId);
    if (present_connId)
      list.add(connId);

    boolean present_clientHostName = true && (isSetClientHostName());
    list.add(present_clientHostName);
    if (present_clientHostName)
      list.add(clientHostName);

    boolean present_clientID = true && (isSetClientID());
    list.add(present_clientID);
    if (present_clientID)
      list.add(clientID);

    boolean present_userName = true && (isSetUserName());
    list.add(present_userName);
    if (present_userName)
      list.add(userName);

    boolean present_token = true && (isSetToken());
    list.add(present_token);
    if (present_token)
      list.add(token);

    boolean present_defaultSchema = true && (isSetDefaultSchema());
    list.add(present_defaultSchema);
    if (present_defaultSchema)
      list.add(defaultSchema);

    return list.hashCode();
  }

  @Override
  public int compareTo(ConnectionProperties other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetConnId()).compareTo(other.isSetConnId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetConnId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.connId, other.connId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetClientHostName()).compareTo(other.isSetClientHostName());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetClientHostName()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.clientHostName, other.clientHostName);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetClientID()).compareTo(other.isSetClientID());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetClientID()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.clientID, other.clientID);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetUserName()).compareTo(other.isSetUserName());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetUserName()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.userName, other.userName);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetToken()).compareTo(other.isSetToken());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetToken()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.token, other.token);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetDefaultSchema()).compareTo(other.isSetDefaultSchema());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetDefaultSchema()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.defaultSchema, other.defaultSchema);
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
    StringBuilder sb = new StringBuilder("ConnectionProperties(");
    boolean first = true;

    sb.append("connId:");
    sb.append(this.connId);
    first = false;
    if (!first) sb.append(", ");
    sb.append("clientHostName:");
    if (this.clientHostName == null) {
      sb.append("null");
    } else {
      sb.append(this.clientHostName);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("clientID:");
    if (this.clientID == null) {
      sb.append("null");
    } else {
      sb.append(this.clientID);
    }
    first = false;
    if (isSetUserName()) {
      if (!first) sb.append(", ");
      sb.append("userName:");
      if (this.userName == null) {
        sb.append("null");
      } else {
        sb.append(this.userName);
      }
      first = false;
    }
    if (isSetToken()) {
      if (!first) sb.append(", ");
      sb.append("token:");
      if (this.token == null) {
        sb.append("null");
      } else {
        org.apache.thrift.TBaseHelper.toString(this.token, sb);
      }
      first = false;
    }
    if (isSetDefaultSchema()) {
      if (!first) sb.append(", ");
      sb.append("defaultSchema:");
      if (this.defaultSchema == null) {
        sb.append("null");
      } else {
        sb.append(this.defaultSchema);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // alas, we cannot check 'connId' because it's a primitive and you chose the non-beans generator.
    if (clientHostName == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'clientHostName' was not present! Struct: " + toString());
    }
    if (clientID == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'clientID' was not present! Struct: " + toString());
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

  private static class ConnectionPropertiesStandardSchemeFactory implements SchemeFactory {
    public ConnectionPropertiesStandardScheme getScheme() {
      return new ConnectionPropertiesStandardScheme();
    }
  }

  private static class ConnectionPropertiesStandardScheme extends StandardScheme<ConnectionProperties> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, ConnectionProperties struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // CONN_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.connId = iprot.readI64();
              struct.setConnIdIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // CLIENT_HOST_NAME
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.clientHostName = iprot.readString();
              struct.setClientHostNameIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // CLIENT_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.clientID = iprot.readString();
              struct.setClientIDIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // USER_NAME
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.userName = iprot.readString();
              struct.setUserNameIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 5: // TOKEN
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.token = iprot.readBinary();
              struct.setTokenIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 6: // DEFAULT_SCHEMA
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.defaultSchema = iprot.readString();
              struct.setDefaultSchemaIsSet(true);
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
      if (!struct.isSetConnId()) {
        throw new org.apache.thrift.protocol.TProtocolException("Required field 'connId' was not found in serialized data! Struct: " + toString());
      }
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, ConnectionProperties struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      oprot.writeFieldBegin(CONN_ID_FIELD_DESC);
      oprot.writeI64(struct.connId);
      oprot.writeFieldEnd();
      if (struct.clientHostName != null) {
        oprot.writeFieldBegin(CLIENT_HOST_NAME_FIELD_DESC);
        oprot.writeString(struct.clientHostName);
        oprot.writeFieldEnd();
      }
      if (struct.clientID != null) {
        oprot.writeFieldBegin(CLIENT_ID_FIELD_DESC);
        oprot.writeString(struct.clientID);
        oprot.writeFieldEnd();
      }
      if (struct.userName != null) {
        if (struct.isSetUserName()) {
          oprot.writeFieldBegin(USER_NAME_FIELD_DESC);
          oprot.writeString(struct.userName);
          oprot.writeFieldEnd();
        }
      }
      if (struct.token != null) {
        if (struct.isSetToken()) {
          oprot.writeFieldBegin(TOKEN_FIELD_DESC);
          oprot.writeBinary(struct.token);
          oprot.writeFieldEnd();
        }
      }
      if (struct.defaultSchema != null) {
        if (struct.isSetDefaultSchema()) {
          oprot.writeFieldBegin(DEFAULT_SCHEMA_FIELD_DESC);
          oprot.writeString(struct.defaultSchema);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class ConnectionPropertiesTupleSchemeFactory implements SchemeFactory {
    public ConnectionPropertiesTupleScheme getScheme() {
      return new ConnectionPropertiesTupleScheme();
    }
  }

  private static class ConnectionPropertiesTupleScheme extends TupleScheme<ConnectionProperties> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, ConnectionProperties struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      oprot.writeI64(struct.connId);
      oprot.writeString(struct.clientHostName);
      oprot.writeString(struct.clientID);
      BitSet optionals = new BitSet();
      if (struct.isSetUserName()) {
        optionals.set(0);
      }
      if (struct.isSetToken()) {
        optionals.set(1);
      }
      if (struct.isSetDefaultSchema()) {
        optionals.set(2);
      }
      oprot.writeBitSet(optionals, 3);
      if (struct.isSetUserName()) {
        oprot.writeString(struct.userName);
      }
      if (struct.isSetToken()) {
        oprot.writeBinary(struct.token);
      }
      if (struct.isSetDefaultSchema()) {
        oprot.writeString(struct.defaultSchema);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, ConnectionProperties struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.connId = iprot.readI64();
      struct.setConnIdIsSet(true);
      struct.clientHostName = iprot.readString();
      struct.setClientHostNameIsSet(true);
      struct.clientID = iprot.readString();
      struct.setClientIDIsSet(true);
      BitSet incoming = iprot.readBitSet(3);
      if (incoming.get(0)) {
        struct.userName = iprot.readString();
        struct.setUserNameIsSet(true);
      }
      if (incoming.get(1)) {
        struct.token = iprot.readBinary();
        struct.setTokenIsSet(true);
      }
      if (incoming.get(2)) {
        struct.defaultSchema = iprot.readString();
        struct.setDefaultSchemaIsSet(true);
      }
    }
  }

}

