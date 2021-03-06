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
package com.gemstone.gemfire.internal.cache.partitioned;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;

/**
 * @author dsmith
 *
 */
public class OfflineMemberDetailsImpl implements OfflineMemberDetails, Serializable, DataSerializable {
  private Set<PersistentMemberID>[] offlineMembers;
  
  //Used for DataSerializer
  public OfflineMemberDetailsImpl() {
    
  }
  
  public OfflineMemberDetailsImpl(Set<PersistentMemberID>[] offlineMembers) {
    this.offlineMembers = offlineMembers;
  }



  public Set<PersistentMemberID> getOfflineMembers(int bucketId) {
    return offlineMembers[bucketId];
  }



  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    int offlineMembersLength = in.readInt();
    this.offlineMembers = new Set[offlineMembersLength];
    for(int i = 0; i < offlineMembersLength; i++) {
      int setSize = in.readInt();
      Set<PersistentMemberID> set= new HashSet<PersistentMemberID>(setSize);
      for(int j = 0; j < setSize; j++) {
        PersistentMemberID id = new PersistentMemberID();
        InternalDataSerializer.invokeFromData(id, in);
        set.add(id);
      }
      this.offlineMembers[i] = set;
    }
  }


  public void toData(DataOutput out) throws IOException {
    out.writeInt(offlineMembers.length);
    for(Set<PersistentMemberID> set : offlineMembers) {
      out.writeInt(set.size());
      for(PersistentMemberID id : set) {
        InternalDataSerializer.invokeToData(id, out);
      }
    }
  }
}
