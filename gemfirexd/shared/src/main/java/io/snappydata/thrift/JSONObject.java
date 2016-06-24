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
public class JSONObject extends org.apache.thrift.TUnion<JSONObject, JSONObject._Fields> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("JSONObject");
  private static final org.apache.thrift.protocol.TField PAIRS_FIELD_DESC = new org.apache.thrift.protocol.TField("pairs", org.apache.thrift.protocol.TType.MAP, (short)1);
  private static final org.apache.thrift.protocol.TField ARRAY_FIELD_DESC = new org.apache.thrift.protocol.TField("array", org.apache.thrift.protocol.TType.LIST, (short)2);

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    PAIRS((short)1, "pairs"),
    ARRAY((short)2, "array");

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
        case 1: // PAIRS
          return PAIRS;
        case 2: // ARRAY
          return ARRAY;
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

  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.PAIRS, new org.apache.thrift.meta_data.FieldMetaData("pairs", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.MapMetaData(org.apache.thrift.protocol.TType.MAP, 
            new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING), 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, JSONValue.class))));
    tmpMap.put(_Fields.ARRAY, new org.apache.thrift.meta_data.FieldMetaData("array", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, JSONValue.class))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(JSONObject.class, metaDataMap);
  }

  public JSONObject() {
    super();
  }

  public JSONObject(_Fields setField, Object value) {
    super(setField, value);
  }

  public JSONObject(JSONObject other) {
    super(other);
  }
  public JSONObject deepCopy() {
    return new JSONObject(this);
  }

  public static JSONObject pairs(Map<String,JSONValue> value) {
    JSONObject x = new JSONObject();
    x.setPairs(value);
    return x;
  }

  public static JSONObject array(List<JSONValue> value) {
    JSONObject x = new JSONObject();
    x.setArray(value);
    return x;
  }


  @Override
  protected void checkType(_Fields setField, Object value) throws ClassCastException {
    switch (setField) {
      case PAIRS:
        if (value instanceof Map) {
          break;
        }
        throw new ClassCastException("Was expecting value of type Map<String,JSONValue> for field 'pairs', but got " + value.getClass().getSimpleName());
      case ARRAY:
        if (value instanceof List) {
          break;
        }
        throw new ClassCastException("Was expecting value of type List<JSONValue> for field 'array', but got " + value.getClass().getSimpleName());
      default:
        throw new IllegalArgumentException("Unknown field id " + setField);
    }
  }

  @Override
  protected Object standardSchemeReadValue(org.apache.thrift.protocol.TProtocol iprot, org.apache.thrift.protocol.TField field) throws org.apache.thrift.TException {
    _Fields setField = _Fields.findByThriftId(field.id);
    if (setField != null) {
      switch (setField) {
        case PAIRS:
          if (field.type == PAIRS_FIELD_DESC.type) {
            Map<String,JSONValue> pairs;
            {
              org.apache.thrift.protocol.TMap _map18 = iprot.readMapBegin();
              pairs = new HashMap<String,JSONValue>(2*_map18.size);
              String _key19;
              JSONValue _val20;
              for (int _i21 = 0; _i21 < _map18.size; ++_i21)
              {
                _key19 = iprot.readString();
                _val20 = new JSONValue();
                _val20.read(iprot);
                pairs.put(_key19, _val20);
              }
              iprot.readMapEnd();
            }
            return pairs;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case ARRAY:
          if (field.type == ARRAY_FIELD_DESC.type) {
            List<JSONValue> array;
            {
              org.apache.thrift.protocol.TList _list22 = iprot.readListBegin();
              array = new ArrayList<JSONValue>(_list22.size);
              JSONValue _elem23;
              for (int _i24 = 0; _i24 < _list22.size; ++_i24)
              {
                _elem23 = new JSONValue();
                _elem23.read(iprot);
                array.add(_elem23);
              }
              iprot.readListEnd();
            }
            return array;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        default:
          throw new IllegalStateException("setField wasn't null, but didn't match any of the case statements!");
      }
    } else {
      org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
      return null;
    }
  }

  @Override
  protected void standardSchemeWriteValue(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    switch (setField_) {
      case PAIRS:
        Map<String,JSONValue> pairs = (Map<String,JSONValue>)value_;
        {
          oprot.writeMapBegin(new org.apache.thrift.protocol.TMap(org.apache.thrift.protocol.TType.STRING, org.apache.thrift.protocol.TType.STRUCT, pairs.size()));
          for (Map.Entry<String, JSONValue> _iter25 : pairs.entrySet())
          {
            oprot.writeString(_iter25.getKey());
            _iter25.getValue().write(oprot);
          }
          oprot.writeMapEnd();
        }
        return;
      case ARRAY:
        List<JSONValue> array = (List<JSONValue>)value_;
        {
          oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, array.size()));
          for (JSONValue _iter26 : array)
          {
            _iter26.write(oprot);
          }
          oprot.writeListEnd();
        }
        return;
      default:
        throw new IllegalStateException("Cannot write union with unknown field " + setField_);
    }
  }

  @Override
  protected Object tupleSchemeReadValue(org.apache.thrift.protocol.TProtocol iprot, short fieldID) throws org.apache.thrift.TException {
    _Fields setField = _Fields.findByThriftId(fieldID);
    if (setField != null) {
      switch (setField) {
        case PAIRS:
          Map<String,JSONValue> pairs;
          {
            org.apache.thrift.protocol.TMap _map27 = iprot.readMapBegin();
            pairs = new HashMap<String,JSONValue>(2*_map27.size);
            String _key28;
            JSONValue _val29;
            for (int _i30 = 0; _i30 < _map27.size; ++_i30)
            {
              _key28 = iprot.readString();
              _val29 = new JSONValue();
              _val29.read(iprot);
              pairs.put(_key28, _val29);
            }
            iprot.readMapEnd();
          }
          return pairs;
        case ARRAY:
          List<JSONValue> array;
          {
            org.apache.thrift.protocol.TList _list31 = iprot.readListBegin();
            array = new ArrayList<JSONValue>(_list31.size);
            JSONValue _elem32;
            for (int _i33 = 0; _i33 < _list31.size; ++_i33)
            {
              _elem32 = new JSONValue();
              _elem32.read(iprot);
              array.add(_elem32);
            }
            iprot.readListEnd();
          }
          return array;
        default:
          throw new IllegalStateException("setField wasn't null, but didn't match any of the case statements!");
      }
    } else {
      throw new TProtocolException("Couldn't find a field with field id " + fieldID);
    }
  }

  @Override
  protected void tupleSchemeWriteValue(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    switch (setField_) {
      case PAIRS:
        Map<String,JSONValue> pairs = (Map<String,JSONValue>)value_;
        {
          oprot.writeMapBegin(new org.apache.thrift.protocol.TMap(org.apache.thrift.protocol.TType.STRING, org.apache.thrift.protocol.TType.STRUCT, pairs.size()));
          for (Map.Entry<String, JSONValue> _iter34 : pairs.entrySet())
          {
            oprot.writeString(_iter34.getKey());
            _iter34.getValue().write(oprot);
          }
          oprot.writeMapEnd();
        }
        return;
      case ARRAY:
        List<JSONValue> array = (List<JSONValue>)value_;
        {
          oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, array.size()));
          for (JSONValue _iter35 : array)
          {
            _iter35.write(oprot);
          }
          oprot.writeListEnd();
        }
        return;
      default:
        throw new IllegalStateException("Cannot write union with unknown field " + setField_);
    }
  }

  @Override
  protected org.apache.thrift.protocol.TField getFieldDesc(_Fields setField) {
    switch (setField) {
      case PAIRS:
        return PAIRS_FIELD_DESC;
      case ARRAY:
        return ARRAY_FIELD_DESC;
      default:
        throw new IllegalArgumentException("Unknown field id " + setField);
    }
  }

  @Override
  protected org.apache.thrift.protocol.TStruct getStructDesc() {
    return STRUCT_DESC;
  }

  @Override
  protected _Fields enumForId(short id) {
    return _Fields.findByThriftIdOrThrow(id);
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }


  public Map<String,JSONValue> getPairs() {
    if (getSetField() == _Fields.PAIRS) {
      return (Map<String,JSONValue>)getFieldValue();
    } else {
      throw new RuntimeException("Cannot get field 'pairs' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setPairs(Map<String,JSONValue> value) {
    if (value == null) throw new NullPointerException();
    setField_ = _Fields.PAIRS;
    value_ = value;
  }

  public List<JSONValue> getArray() {
    if (getSetField() == _Fields.ARRAY) {
      return (List<JSONValue>)getFieldValue();
    } else {
      throw new RuntimeException("Cannot get field 'array' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setArray(List<JSONValue> value) {
    if (value == null) throw new NullPointerException();
    setField_ = _Fields.ARRAY;
    value_ = value;
  }

  public boolean isSetPairs() {
    return setField_ == _Fields.PAIRS;
  }


  public boolean isSetArray() {
    return setField_ == _Fields.ARRAY;
  }


  public boolean equals(Object other) {
    if (other instanceof JSONObject) {
      return equals((JSONObject)other);
    } else {
      return false;
    }
  }

  public boolean equals(JSONObject other) {
    return other != null && getSetField() == other.getSetField() && getFieldValue().equals(other.getFieldValue());
  }

  @Override
  public int compareTo(JSONObject other) {
    int lastComparison = org.apache.thrift.TBaseHelper.compareTo(getSetField(), other.getSetField());
    if (lastComparison == 0) {
      return org.apache.thrift.TBaseHelper.compareTo(getFieldValue(), other.getFieldValue());
    }
    return lastComparison;
  }


  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();
    list.add(this.getClass().getName());
    org.apache.thrift.TFieldIdEnum setField = getSetField();
    if (setField != null) {
      list.add(setField.getThriftFieldId());
      Object value = getFieldValue();
      if (value instanceof org.apache.thrift.TEnum) {
        list.add(((org.apache.thrift.TEnum)getFieldValue()).getValue());
      } else {
        list.add(value);
      }
    }
    return list.hashCode();
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


}
