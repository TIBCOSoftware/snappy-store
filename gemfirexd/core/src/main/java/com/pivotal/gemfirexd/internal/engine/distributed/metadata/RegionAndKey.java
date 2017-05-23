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

package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * 
 * @author kneeraj
 * 
 */
public final class RegionAndKey extends GfxdDataSerializable implements
    Serializable, Comparable<RegionAndKey> {

  private static final long serialVersionUID = 2599723689085422219L;

  private String rname;

  private Object key;

  private boolean isReplicated;

//  private boolean isDriver = false;
  public RegionAndKey() {
  }

  public RegionAndKey(String regionName, Object key, boolean rep) {
    this.rname = regionName;
    this.key = key;
    this.isReplicated = rep;
  }

  public boolean isDriver(String driverTable) {
    if (forSpecialUseOfSetOperators) {
      SanityManager.ASSERT(key == null, 
      " RegionAndKey when used for set operators, should have no key ");
      SanityManager.ASSERT(rname == null, 
      " RegionAndKey when used for set operators, should have no key ");
      return false;
    }
    
    return driverTable
        .equals(this.rname.substring(this.rname.lastIndexOf('.') + 1));
    ///assert this.isReplicated;
  }

  public boolean isReplicatedRegion() {
    return this.isReplicated;
  }

  @Override
  public byte getGfxdID() {
    return GfxdSerializable.GFXD_REP_TABLE_KEYS;
  }

  @Override
  public void fromData(DataInput in) throws IOException,
          ClassNotFoundException {
    super.fromData(in);
    this.forSpecialUseOfSetOperators = in.readBoolean();
    if (this.forSpecialUseOfSetOperators) {
      this.leftSideTreeOfSetOperatorNode = in.readBoolean();
    }
    else {
      this.rname = DataSerializer.readString(in);
      this.key = DataSerializer.readObject(in);
      if (this.rname != null && this.key instanceof KeyWithRegionContext) {
        try {
          LocalRegion r = (LocalRegion)Misc.getRegionForTable(this.rname, true);
          ((KeyWithRegionContext)this.key).setRegionContext(r);
        } catch (CancelException e) {
          // ignore in deserialization (SNAP-1488)
        }
      }
      this.isReplicated = in.readBoolean();
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    out.writeBoolean(this.forSpecialUseOfSetOperators);
    if (forSpecialUseOfSetOperators) {
      out.writeBoolean(this.leftSideTreeOfSetOperatorNode);
    } 
    else { 
      DataSerializer.writeString(this.rname, out);
      DataSerializer.writeObject(this.key, out);
      out.writeBoolean(this.isReplicated);
    }
  }

  @Override
  public boolean equals(Object obj) {
    assert obj instanceof RegionAndKey: "unexpected obj type: "
        + obj.getClass().getName();
    RegionAndKey other = (RegionAndKey)obj;
    
    if (forSpecialUseOfSetOperators || other.forSpecialUseOfSetOperators) {
      if (forSpecialUseOfSetOperators) {
        SanityManager.ASSERT(key == null, 
        " RegionAndKey when used for set operators, should have no key ");
        SanityManager.ASSERT(rname == null, 
        " RegionAndKey when used for set operators, should have no key ");
      }

      return (this.forSpecialUseOfSetOperators == other.forSpecialUseOfSetOperators
          && this.leftSideTreeOfSetOperatorNode == other.leftSideTreeOfSetOperatorNode);
    }
    
    if (this.isReplicated == other.isReplicated
        && this.rname.equalsIgnoreCase(other.rname)
        && this.key.equals(other.key)) {
      return true;
    }
    return false;
  }

  @Override
  public int hashCode() {
    if (forSpecialUseOfSetOperators) {
      SanityManager.ASSERT(key == null, 
      " RegionAndKey when used for set operators, should have no key ");
      SanityManager.ASSERT(rname == null, 
      " RegionAndKey when used for set operators, should have no key ");

      if (this.leftSideTreeOfSetOperatorNode) {
        return 1;
      } else {
        return 2;
      }
    }
    
    return this.rname.hashCode() ^ this.key.hashCode();
  }

  @Override
  public String toString() {
    if (forSpecialUseOfSetOperators) {
      SanityManager.ASSERT(key == null, 
      " RegionAndKey when used for set operators, should have no key ");
      SanityManager.ASSERT(rname == null, 
      " RegionAndKey when used for set operators, should have no key ");

      return "region for special-use-for-set-operators "
      + " have leftSideTreeOfSetOperatorNode = " 
      + this.leftSideTreeOfSetOperatorNode;
    }
    
    return "region: " + this.rname + (this.isReplicated ? "(R)" : "(P)")
      + " and key: " + this.key;
  }

  public int compareTo(RegionAndKey other) {
    if (forSpecialUseOfSetOperators || other.forSpecialUseOfSetOperators) {
      if (forSpecialUseOfSetOperators) {
        SanityManager.ASSERT(key == null, 
        " RegionAndKey when used for set operators, should have no key ");
        SanityManager.ASSERT(rname == null, 
        " RegionAndKey when used for set operators, should have no key ");
      }

      return (this.forSpecialUseOfSetOperators == other.forSpecialUseOfSetOperators
          && this.leftSideTreeOfSetOperatorNode == other.leftSideTreeOfSetOperatorNode ? 
              1 : -1);
    }
    
    assert this.key != null && other.key != null;
    
    if (this.isReplicated) {
      if (!other.isReplicated) {
        return -1;
      }
    }
    else if (other.isReplicated) {
      return 1;
    }

    if (this.rname == null ) {
      if (other.rname != null) {
        return 1;
      }
      else {
        return (this.key.hashCode() > other.key.hashCode() ? -1 : 1);
      }
    }
    if (this.rname.equals(other.rname)) {
      if (this.key.equals(other.key)) {
        return 0;
      }
      else {
        return (this.key.hashCode() > other.key.hashCode() ? -1 : 1);
      }
    }else {           
        return (this.rname.hashCode() > other.rname.hashCode() ? -1 : 1);    
      
    }
    
  }

  public String getRegionName() {
    return this.rname;
  }
  
  public Object getKey() {
    return this.key;
  }
  
  public RegionAndKey getClone() {
    if (forSpecialUseOfSetOperators) {
      SanityManager.ASSERT(key == null, 
      " RegionAndKey when used for set operators, should have no key ");
      SanityManager.ASSERT(rname == null, 
      " RegionAndKey when used for set operators, should have no key ");
      
      return this.leftSideTreeOfSetOperatorNode
          ? RegionAndKey.TRUE : RegionAndKey.FALSE;
    }
    return new RegionAndKey(this.rname, this.key, this.isReplicated);
  }
  
  public long estimateMemoryUsage() throws StandardException {
    if (forSpecialUseOfSetOperators) {
      SanityManager.ASSERT(key == null, 
      " RegionAndKey when used for set operators, should have no key ");
      SanityManager.ASSERT(rname == null, 
      " RegionAndKey when used for set operators, should have no key ");
      
      // 2 boolean
      return 2;
    }
    
    long memory = 0;
    if(key instanceof CompactCompositeKey) {
      memory += ((CompactCompositeKey)key).estimateMemoryUsage();
    }
    else if (key instanceof DataValueDescriptor) {
      memory += ((DataValueDescriptor)key).estimateMemoryUsage();
    }
    else if (key instanceof Long) {
      memory += (Long.SIZE/8);
    }
    
    return rname.length() + memory;
  }
  
  /*
   * For special use in Set Operator Operations.
   * Convert Booleans to Byte based information
   */
  private boolean forSpecialUseOfSetOperators;
  
  public boolean isForSpecialUseOfSetOperators() {
    return forSpecialUseOfSetOperators;
  }
  
  private boolean leftSideTreeOfSetOperatorNode;
  
  public boolean isLeftSideTreeOfSetOperatorNode() {
    return leftSideTreeOfSetOperatorNode;
  }

  public static final RegionAndKey TRUE = new RegionAndKey(true);
  public static final RegionAndKey FALSE = new RegionAndKey(false);

  private RegionAndKey(boolean isLeftSideTreeOfSetOperatorNode) {
    forSpecialUseOfSetOperators = true;
    leftSideTreeOfSetOperatorNode = isLeftSideTreeOfSetOperatorNode;
  }  
}
