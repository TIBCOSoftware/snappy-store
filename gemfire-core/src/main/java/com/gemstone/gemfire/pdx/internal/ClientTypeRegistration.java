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
package com.gemstone.gemfire.pdx.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.ServerConnectivityException;
import com.gemstone.gemfire.cache.client.internal.AddPDXEnumOp;
import com.gemstone.gemfire.cache.client.internal.AddPDXTypeOp;
import com.gemstone.gemfire.cache.client.internal.ExecutablePool;
import com.gemstone.gemfire.cache.client.internal.GetPDXEnumByIdOp;
import com.gemstone.gemfire.cache.client.internal.GetPDXEnumsOp;
import com.gemstone.gemfire.cache.client.internal.GetPDXIdForEnumOp;
import com.gemstone.gemfire.cache.client.internal.GetPDXIdForTypeOp;
import com.gemstone.gemfire.cache.client.internal.GetPDXTypeByIdOp;
import com.gemstone.gemfire.cache.client.internal.GetPDXTypesOp;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GatewayImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PoolManagerImpl;
import com.gemstone.gemfire.pdx.PdxInitializationException;

/**
 * @author dsmith
 *
 */
public class ClientTypeRegistration implements TypeRegistration {
  private final GemFireCacheImpl cache;
  
  private volatile boolean typeRegistryInUse = false;

  public ClientTypeRegistration(GemFireCacheImpl cache) {
    this.cache = cache;
  }
  
  public int defineType(PdxType newType) {
    verifyConfiguration(); 
    Collection<Pool> pools = getAllPools();
    String poolToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      try {
        if (poolToUse != null && !pool.getName().equalsIgnoreCase(poolToUse)) {
          continue;
        }
        int result = GetPDXIdForTypeOp.execute((ExecutablePool) pool, newType);
        newType.setTypeId(result);
        sendTypeToAllPools(newType, result, pools, pool);
        return result;
      } catch(ServerConnectivityException e) {
        //ignore, try the next pool.
        lastException = e;
      }

      if (poolToUse != null) {
        break;
      }
    }
    if(lastException != null) {
      throw lastException;
    } else {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry can not define a type.");
    }
  }
  
  private void sendTypeToAllPools(PdxType type, int id,
      Collection<Pool> pools, Pool definingPool) {
    
    for(Pool pool: pools) {
      if(pool.equals(definingPool)) {
        continue;
      }
      
      try {
        AddPDXTypeOp.execute((ExecutablePool) pool, id, type);
      } catch(ServerConnectivityException ignore) {
        getLogger().fine("Received an exception sending pdx type to pool " + pool + ", " + ignore);
        //TODO DAN - is it really safe to ignore this? What if this is the pool
        //we're about to do a put on? I think maybe we really should pass the context
        //down to this point, if it is available. Maybe just an optional thread local?
        //Then we could go straight to that pool to register the type and bail otherwise.
      }
    }
    
  }

  public PdxType getType(int typeId) {
    verifyConfiguration();
    Collection<Pool> pools = getAllPools();
    String poolToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      if (poolToUse != null && !pool.getName().equalsIgnoreCase(poolToUse)) {
          continue;
      }
      try {
        PdxType type = GetPDXTypeByIdOp.execute((ExecutablePool) pool, typeId);
        if(type != null) {
          return type;
        }
      } catch(ServerConnectivityException e) {
        getLogger().fine("Received an exception getting pdx type from pool " + pool + ", " + e);
        //ignore, try the next pool.
        lastException = e;
      }

      if (poolToUse != null) {
        break;
      }
    }
    
    if(lastException != null) {
      throw lastException;
    } else {
      if(pools.isEmpty()) {
        // TODO check for cache closed
        if (this.cache.isClosed()) {
          throw new CacheClosedException("PDX detected cache was closed");
        }
        throw new CacheClosedException("Client pools have been closed so the PDX type registry can not lookup a type.");
      } else {
        throw new InternalGemFireError("getType: Unable to determine PDXType for id " + typeId + " from existing client to server pools " + pools);
      }
    }
  }
  
  private Collection<Pool> getAllPools() {
    return getAllPools(cache);
  }
  
  private LogWriterI18n getLogger() {
    return cache.getLoggerI18n();
  }
  
  private static Collection<Pool> getAllPools(GemFireCacheImpl cache) {
    Collection<Pool> pools = PoolManagerImpl.getPMI().getMap().values();
    for(Iterator<Pool> itr= pools.iterator(); itr.hasNext(); ) {
      PoolImpl pool = (PoolImpl) itr.next();
      if(pool.isUsedByGateway()) {
        itr.remove();
      }
    }
    return pools;
  }

  public void addRemoteType(int typeId, PdxType type) {
    throw new UnsupportedOperationException("Clients will not be asked to add remote types");
  }

  public int getLastAllocatedTypeId() {
    throw new UnsupportedOperationException("Clients does not keep track of last allocated id");
  }

  public void initialize() {
    //do nothing
  }

  public void gatewayStarted(GatewayImpl gatewayImpl) {
    checkAllowed(true);
  }

  public void gatewaySenderStarted(GatewaySender gatewaySender) {
    checkAllowed(true);
  }
  
  public void creatingPersistentRegion() {
    //do nothing
  }

  public void startingGatewayHub() {
    checkAllowed(true);
  }

  public void creatingPool() {
    //do nothing
  }

  public int getEnumId(Enum<?> v) {
    EnumInfo ei = new EnumInfo(v);
    Collection<Pool> pools = getAllPools();
    String gridToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      if (gridToUse != null && !pool.getName().equalsIgnoreCase(gridToUse)) {
        continue;
      }
      try {
        int result = GetPDXIdForEnumOp.execute((ExecutablePool) pool, ei);
        sendEnumIdToAllPools(ei, result, pools, pool);
        return result;
      } catch(ServerConnectivityException e) {
        //ignore, try the next pool.
        lastException = e;
      }

      if (gridToUse != null) {
        break;
      }
    }
    if (lastException != null) {
      throw lastException;
    } else {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry can not define a type.");
    }
  }
  
  private void sendEnumIdToAllPools(EnumInfo enumInfo, int id,
      Collection<Pool> pools, Pool definingPool) {

    for (Pool pool: pools) {
      if (pool.equals(definingPool)) {
        continue;
      }

      try {
        AddPDXEnumOp.execute((ExecutablePool) pool, id, enumInfo);
      } catch(ServerConnectivityException ignore) {
        getLogger().fine("Received an exception sending pdx type to pool " + pool + ", " + ignore);
        //TODO DAN - is it really safe to ignore this? What if this is the pool
        //we're about to do a put on? I think maybe we really should pass the context
        //down to this point, if it is available. Maybe just an optional thread local?
        //Then we could go straight to that pool to register the type and bail otherwise.
      }
    }
  }

  public void addRemoteEnum(int enumId, EnumInfo newInfo) {
    throw new UnsupportedOperationException("Clients will not be asked to add remote enums");
  }

  public int defineEnum(EnumInfo newInfo) {
    Collection<Pool> pools = getAllPools();
    String gridToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      if (gridToUse != null && !pool.getName().equalsIgnoreCase(gridToUse)) {
        continue;
      }
      try {
        int result = GetPDXIdForEnumOp.execute((ExecutablePool) pool, newInfo);
        sendEnumIdToAllPools(newInfo, result, pools, pool);
        return result;
      } catch(ServerConnectivityException e) {
        //ignore, try the next pool.
        lastException = e;
      }
      if (gridToUse != null) {
        break;
      }
    }
    
    
    if(lastException != null) {
      throw lastException;
    } else {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry can not define a type.");
    }
   }

  public EnumInfo getEnumById(int enumId) {
    Collection<Pool> pools = getAllPools();
    String gridToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      if (gridToUse != null && !pool.getName().equalsIgnoreCase(gridToUse)) {
        continue;
      }
      try {
        EnumInfo result = GetPDXEnumByIdOp.execute((ExecutablePool) pool, enumId);
        if(result != null) {
          return result;
        }
      } catch(ServerConnectivityException e) {
        getLogger().fine("Received an exception getting pdx type from pool " + pool + ", " + e);
        //ignore, try the next pool.
        lastException = e;
      }

      if (gridToUse != null) {
        break;
      }
    }
    
    if(lastException != null) {
      throw lastException;
    } else {
      if(pools.isEmpty()) {
        // TODO check for cache closed
        if (this.cache.isClosed()) {
          throw new CacheClosedException("PDX detected cache was closed");
        }
        throw new CacheClosedException("Client pools have been closed so the PDX type registry can not lookup an enum.");
      } else {
        throw new InternalGemFireError("getEnum: Unable to determine pdx enum for id " + enumId + " from existing client to server pools " + pools);
      }
    }
  }
  
  private void verifyConfiguration() {
    if(typeRegistryInUse) {
      return;
    } else {
      typeRegistryInUse = true;
      checkAllowed(!cache.getGatewayHubs().isEmpty());
    }
  }
  
  private void checkAllowed(boolean hasGateway) {
    //Anything is allowed until the registry is in use.
    if(!typeRegistryInUse) {
      return;
    }
    
    if(hasGateway) {
      throw new PdxInitializationException("PDX does not allow both client pools and gateways in the same member.");
    }
  }

  @SuppressWarnings({ "unchecked", "serial" })
  @Override
  public Map<Integer, PdxType> types() {
    Collection<Pool> pools = getAllPools();
    if (pools.isEmpty()) {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry is not available.");
    }
    
    Map<Integer, PdxType> types = new HashMap<Integer, PdxType>();
    for (Pool p : pools) {
      try {
        types.putAll(GetPDXTypesOp.execute((ExecutablePool) p));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return types;
  }

  @SuppressWarnings({ "unchecked", "serial" })
  @Override
  public Map<Integer, EnumInfo> enums() {
    Collection<Pool> pools = getAllPools();
    if (pools.isEmpty()) {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry is not available.");
    }
    
    Map<Integer, EnumInfo> enums = new HashMap<Integer, EnumInfo>();
    for (Pool p : pools) {
      enums.putAll(GetPDXEnumsOp.execute((ExecutablePool) p));
    }
    return enums;
  }
  

  @Override
  public PdxType getPdxTypeForField(String fieldName, String className) {
    for (Object value : types().values()) {
      if (value instanceof PdxType){
        PdxType pdxType = (PdxType) value;
        if(pdxType.getClassName().equals(className) && pdxType.getFieldNames().contains(fieldName)){
          return pdxType;
        }
      }
    }
    return null;
  }
  @Override
  public void testClearRegistry() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public boolean isClient() {
    return true;
  }

  @Override
  public void addImportedType(int typeId, PdxType importedType) {
    Collection<Pool> pools = getAllPools();
    String gridToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      if (gridToUse != null && !pool.getName().equalsIgnoreCase(gridToUse)) {
        continue;
      }
      try {
        sendTypeToAllPools(importedType, typeId, pools, pool);
      } catch(ServerConnectivityException e) {
        //ignore, try the next pool.
        lastException = e;
      }
      if(gridToUse != null) {
        break;
      }
    }
    if(lastException != null) {
      throw lastException;
    } else {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry can not define a type.");
    }
  }

  @Override
  public void addImportedEnum(int enumId, EnumInfo importedInfo) {
    Collection<Pool> pools = getAllPools();
    String gridToUse = TypeRegistry.getGridNameForPdxType();
    ServerConnectivityException lastException = null;
    for(Pool pool: pools) {
      if (gridToUse != null && !pool.getName().equalsIgnoreCase(gridToUse)) {
        continue;
      }
      try {
        sendEnumIdToAllPools(importedInfo, enumId, pools, pool);
      } catch(ServerConnectivityException e) {
        //ignore, try the next pool.
        lastException = e;
      }

      if(gridToUse != null) {
        break;
      }
    }
    
    if(lastException != null) {
      throw lastException;
    } else {
      if (this.cache.isClosed()) {
        throw new CacheClosedException("PDX detected cache was closed");
      }
      throw new CacheClosedException("Client pools have been closed so the PDX type registry can not define a type.");
    }
  }
}
