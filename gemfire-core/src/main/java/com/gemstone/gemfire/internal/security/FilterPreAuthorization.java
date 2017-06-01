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

package com.gemstone.gemfire.internal.security;

import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.Instantiator;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.operations.PutAllOperationContext;
import com.gemstone.gemfire.cache.operations.PutOperationContext;
import com.gemstone.gemfire.cache.operations.OperationContext;
import com.gemstone.gemfire.cache.operations.OperationContext.OperationCode;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.CachedDeserializableFactory;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.security.AccessControl;
import com.gemstone.gemfire.security.NotAuthorizedException;

/**
 * An authorization implementation for testing that changes a string value in
 * pre-operation phase to add an integer denoting which <code>Principal</code>s
 * would be allowed to get that object.
 * 
 * @author Sumedh Wale
 * @since 5.5
 */
public class FilterPreAuthorization implements AccessControl {

  private LogWriterI18n logger;

  static {
    Instantiator.register(new Instantiator(ObjectWithAuthz.class,
        ObjectWithAuthz.CLASSID) {
      @Override
      public DataSerializable newInstance() {
        return new ObjectWithAuthz();
      }
    }, false);
  }

  public FilterPreAuthorization() {

    this.logger = null;
  }

  public static AccessControl create() {

    return new FilterPreAuthorization();
  }

  public void init(Principal principal, DistributedMember remoteMember,
      Cache cache) throws NotAuthorizedException {

    this.logger = cache.getSecurityLoggerI18n();
  }

  public boolean authorizeOperation(String regionName, OperationContext context) {

    assert !context.isPostOperation();
    OperationCode opCode = context.getOperationCode();
    if (opCode.isPut()) {
      PutOperationContext createContext = (PutOperationContext)context;
//      byte[] serializedValue = createContext.getSerializedValue();
      byte[] serializedValue = null;
      Object value = createContext.getValue();
      int valLength;
      byte lastByte;
      if (value == null) {
        // This means serializedValue too is null.
        valLength = 0;
        lastByte = 0;
      }
      else {
        if (value instanceof byte[]) {
          serializedValue = (byte[])value;
          valLength = serializedValue.length;
          lastByte = serializedValue[valLength - 1];
        }
        else {
          ObjectWithAuthz authzObj = new ObjectWithAuthz(value, Integer.valueOf(
              value.hashCode()));
          createContext.setValue(authzObj, true);
          return true;
        }
      }
      HeapDataOutputStream hos = new HeapDataOutputStream(valLength + 32,
          Version.CURRENT_GFE);
      try {
        InternalDataSerializer.writeUserDataSerializableHeader(
            ObjectWithAuthz.CLASSID, hos);
        if (serializedValue != null) {
          hos.write(serializedValue);
        }
        // Some value that determines the Principals that can get this object.
        Integer allowedIndex = Integer.valueOf(lastByte);
        DataSerializer.writeObject(allowedIndex, hos);
      } catch (Exception ex) {
        return false;
      }
      createContext.setSerializedValue(hos.toByteArray(), true);
      if (this.logger.fineEnabled()) 
      this.logger.fine("FilterPreAuthorization: added authorization "
          + "info for key: " + createContext.getKey());
    }
    else if (opCode.isPutAll()) {
      PutAllOperationContext createContext = (PutAllOperationContext)context;
      HashMap map = (HashMap)createContext.getMap();
      Collection entries = map.entrySet();
      Iterator iterator = entries.iterator();
      Map.Entry mapEntry = null;
      while (iterator.hasNext()) {
        mapEntry = (Map.Entry)iterator.next();
        String currkey = (String)mapEntry.getKey();
        Object value = mapEntry.getValue();
        if (CachedDeserializableFactory.preferObject()) {
          ObjectWithAuthz authzObj = new ObjectWithAuthz(value,
              Integer.valueOf(value.hashCode()));
          map.put(currkey, authzObj);
          continue;
        }
        byte[] serializedValue = ((CachedDeserializable)value)
            .getSerializedValue();
        int valLength;
        byte lastByte;
        if (serializedValue == null) {
          valLength = 0;
          lastByte = 0;
        }
        else {
          valLength = serializedValue.length;
          lastByte = serializedValue[valLength - 1];
        }
        HeapDataOutputStream hos = new HeapDataOutputStream(valLength + 32,
            Version.CURRENT_GFE);
        try {
          InternalDataSerializer.writeUserDataSerializableHeader(
              ObjectWithAuthz.CLASSID, hos);
          if (serializedValue != null) {
            hos.write(serializedValue);
          }
          // Some value that determines the Principals that can get this object.
          Integer allowedIndex = Integer.valueOf(lastByte);
          DataSerializer.writeObject(allowedIndex, hos);
        } catch (Exception ex) {
          return false;
        }
        byte[] auth_byte_array = hos.toByteArray();
        // we need to wrap the byte array into CachedDeserializable to tell
        // putAll it's an object, not a real byte array
        map.put(currkey, CachedDeserializableFactory.create(auth_byte_array));
        if (this.logger.fineEnabled()) 
        this.logger.fine("FilterPreAuthorization: putAll: added authorization "
            + "info for key: " + currkey);
      }
      createContext.setMap(map);
      // Now each of the map's values have become auth_byte_array
    }
    return true;
  }

  public void close() {
  }

}
