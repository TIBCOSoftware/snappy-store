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
package com.gemstone.gemfire.internal.cache.versions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import junit.framework.TestCase;

import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.shared.Version;

public class RegionVersionVectorJUnitTest extends TestCase {


  public void testRVVGII() {

    DiskStoreID id1 = new DiskStoreID(1, 0);
    DiskStoreID id2 = new DiskStoreID(2, 0);

    DiskRegionVersionVector rvv1 = new DiskRegionVersionVector(id1);

    for(int i=1;i<19;i++) {
      rvv1.recordVersion(id1,i,null);
    }
    for(int i=1;i<10;i++) {
      rvv1.recordVersion(id2,i,null);
    }

    System.out.println("This node init, rvv1= "+ rvv1.fullToString());

    DiskRegionVersionVector rvv2 = new DiskRegionVersionVector(id2);

    for(int i=1;i<20;i++) {
      rvv2.recordVersion(id1,i,null);
    }
    for(int i=1;i<12;i++) {
      rvv2.recordVersion(id2,i,null);
    }


    System.out.println("This node init, rvv2="+rvv2.fullToString());

    rvv1.recordVersions(rvv2, null);


    System.out.println("This node init, rvv1="+rvv1.fullToString());

    rvv1.getCurrentVersion();
    System.out.println("This node init, rvv1="+rvv1.fullToString());

    assert(rvv1.fullToString().contentEquals("RegionVersionVector[00000000-0000-0001-0000-000000000000={rv19 gc0 localVersion=19 local exceptions=[]} others={00000000-0000-0001-0000-000000000000={rv19 bsv1 bs=[], 00000000-0000-0002-0000-000000000000={rv11 bsv11 bs=[0]}, gc={}]"));
    assert(rvv1.contains(id1,19));
  }

  public void testExceptionsWithContains() {
    DiskStoreID ownerId = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(1, 0);
    
    DiskRegionVersionVector rvv = new DiskRegionVersionVector(ownerId);
    
    doExceptionsWithContains(ownerId, rvv);
    doExceptionsWithContains(id1, rvv);
  }

  public void testRVVSerialization() throws IOException, ClassNotFoundException {
    DiskStoreID ownerId = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(1, 0);
    
    DiskRegionVersionVector rvv = new DiskRegionVersionVector(ownerId);
    rvv.recordVersion(id1, 5);
    rvv.recordVersion(id1, 6);
    rvv.recordVersion(id1, 7);
    rvv.recordVersion(id1, 9);
    rvv.recordVersion(id1, 20);
    rvv.recordVersion(id1, 11);
    rvv.recordVersion(id1, 12);
    rvv.recordGCVersion(id2, 5);
    rvv.recordGCVersion(id1, 3);
    
    assertTrue(rvv.sameAs(rvv.getCloneForTransmission()));
    
    HeapDataOutputStream out = new HeapDataOutputStream(Version.CURRENT);
    InternalDataSerializer.writeObject(rvv.getCloneForTransmission(), out);
    byte[] bytes = out.toByteArray();
    
    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
    DiskRegionVersionVector rvv2 = InternalDataSerializer.readObject(dis);
    
    assertTrue(rvv.sameAs(rvv2));
  }

  public void testRVVSnapshot() throws IOException, ClassNotFoundException {
    DiskStoreID ownerId = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(1, 0);

    DiskRegionVersionVector rvv = new DiskRegionVersionVector(ownerId);

    rvv.recordVersion(id1, 2);
    rvv.recordVersion(id1, 1);
    rvv.recordVersion(id1, 7);
    rvv.recordVersion(id1, 9);
    rvv.recordVersion(id1, 20);
    rvv.recordVersion(id1, 11);
    rvv.recordVersion(id1, 12);
    rvv.recordGCVersion(id2, 5);
    rvv.recordGCVersion(id1, 3);

    ConcurrentHashMap<DiskStoreID, RegionVersionHolder<DiskStoreID>> vector = rvv.getCopyOfSnapShotOfMemberVersion();
    ConcurrentHashMap<DiskStoreID, Long> gcVersions = rvv.getMemberToGCVersionTest();
    DiskRegionVersionVector snapShotRvv = new DiskRegionVersionVector(ownerId, vector, rvv.getCurrentVersion(),
        gcVersions, rvv.getGCVersion(null), false, rvv.getLocalExceptions());

    assertTrue(rvv.sameAs(snapShotRvv));
  }

  public void testRVVSnapshotContains() throws IOException, ClassNotFoundException {
    DiskStoreID ownerId = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(1, 0);

    DiskRegionVersionVector rvv = new DiskRegionVersionVector(ownerId);

    for(int i=0; i< 57; i++) {
      rvv.recordVersion(id1, i);
    }
    rvv.recordVersion(id1, 60);
    rvv.recordVersion(id1, 58);

    ConcurrentHashMap<DiskStoreID, RegionVersionHolder<DiskStoreID>> vector = rvv.getCopyOfSnapShotOfMemberVersion();

    assertTrue(vector.get(id1).contains(2));
    assertTrue(vector.get(id1).contains(1));
    assertTrue(vector.get(id1).contains(7));
    assertTrue(vector.get(id1).contains(9));
    assertTrue(vector.get(id1).contains(20));
    assertTrue(vector.get(id1).contains(11));
    assertTrue(vector.get(id1).contains(12));
    assertTrue(vector.get(id1).contains(3));
  }

  /**
   * Test that we can copy the member to version map correctly.
   */
  public void testCopyMemberToVersion() {
    DiskStoreID id0 = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(1, 0);
    
    DiskRegionVersionVector rvv0 = new DiskRegionVersionVector(id0);
    rvv0.getNextVersion();
    rvv0.getNextVersion();
    rvv0.getNextVersion();
    
    rvv0.recordVersion(id1, 1);
    rvv0.recordVersion(id1, 3);
    
    DiskRegionVersionVector rvv1 = new DiskRegionVersionVector(id1);
    rvv1.recordVersions(rvv0);
    
    assertEquals(3, rvv1.getCurrentVersion());
    assertFalse(rvv1.contains(id1, 2));
    assertTrue(rvv1.contains(id1, 1));
    assertTrue(rvv1.contains(id1, 3));
    
    assertTrue(rvv1.contains(id0, 3));
    
    assertTrue(rvv0.sameAs(rvv1));
    
    rvv1.recordVersion(id1, 2);
    assertTrue(rvv1.isNewerThanOrCanFillExceptionsFor(rvv0));
    assertFalse(rvv0.isNewerThanOrCanFillExceptionsFor(rvv1));
    assertTrue(rvv1.dominates(rvv0));
    assertFalse(rvv0.dominates(rvv1));
  }
  
  public void testSpecialException() {
    DiskStoreID id0 = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(1, 0);
    
    DiskRegionVersionVector rvv0 = new DiskRegionVersionVector(id0);
    rvv0.getNextVersion();
    rvv0.getNextVersion();
    rvv0.getNextVersion();
    
    rvv0.recordVersion(id1, 1);
    rvv0.recordVersion(id1, 2);
    
    DiskRegionVersionVector rvv1 = new DiskRegionVersionVector(id1);
    rvv1.recordVersions(rvv0);

    rvv1.recordVersion(id1, 3);
    RegionVersionHolder holder_at_rvv1 = rvv1.getLocalExceptions();
    RegionVersionHolder holder_at_rvv0 = rvv0.getMemberToVersion().get(id1);
    holder_at_rvv1.addException(2,4);
    assertFalse(rvv1.isNewerThanOrCanFillExceptionsFor(rvv0));
    assertFalse(rvv0.isNewerThanOrCanFillExceptionsFor(rvv1));
    assertTrue(rvv1.dominates(rvv0));
    assertTrue(rvv0.dominates(rvv1));
  }
  
  public void test48066_1() {
    DiskStoreID id0 = new DiskStoreID(0, 0);
    DiskRegionVersionVector rvv0 = new DiskRegionVersionVector(id0);
    for (int i=1; i<=3; i++) {
      rvv0.recordVersion(id0, i);
    }
    System.out.println("rvv0="+rvv0.fullToString());

    DiskRegionVersionVector rvv1 = (DiskRegionVersionVector)rvv0.getCloneForTransmission();
    System.out.println("after clone, rvv1="+rvv1.fullToString());

    DiskRegionVersionVector rvv2 = new DiskRegionVersionVector(id0);
    for (int i=1; i<=10; i++) {
      rvv2.recordVersion(id0, i);
    }
    rvv2.recordVersions(rvv1);
    System.out.println("after init, rvv2="+rvv2.fullToString());
    
    rvv2.recordVersion(id0, 4);
    System.out.println("after record 4, rvv2="+rvv2.fullToString());
    assertEquals(4, rvv2.getCurrentVersion());
    
    rvv2.recordVersion(id0, 7);
    System.out.println("after record 7, rvv2="+rvv2.fullToString());
    assertEquals(7, rvv2.getCurrentVersion());
  }
  
  public void test48066_2() {
    DiskStoreID id0 = new DiskStoreID(0, 0);
    DiskRegionVersionVector rvv0 = new DiskRegionVersionVector(id0);
    for (int i=1; i<=10; i++) {
      rvv0.recordVersion(id0, i);
    }
    
    DiskRegionVersionVector rvv1 = new DiskRegionVersionVector(id0);
    rvv0.recordVersions(rvv1);
    System.out.println("rvv0="+rvv0.fullToString());
    
    rvv0.recordVersion(id0, 4);
    System.out.println("after record 4, rvv0="+rvv0.fullToString());
    assertEquals(4, rvv0.getCurrentVersion());

    rvv0.recordVersion(id0, 7);
    System.out.println("after record 7, rvv0="+rvv0.fullToString());
    assertEquals(7, rvv0.getCurrentVersion());
    assertFalse(rvv0.contains(id0, 5));

    DiskRegionVersionVector rvv2 = (DiskRegionVersionVector)rvv0.getCloneForTransmission(); 
    System.out.println("after clone, rvv2="+rvv2.fullToString());
    assertEquals(11, rvv0.getNextVersion());
    assertFalse(rvv2.contains(id0, 5));
    assertEquals(11, rvv2.getNextVersion());
  }
  
  /**
   * Test for bug 47023. Make sure recordGCVersion works
   * correctly and doesn't generate exceptions for the local member.
   * 
   */
  public void testRecordGCVersion() {
    DiskStoreID id0 = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    
    DiskRegionVersionVector rvv0 = new DiskRegionVersionVector(id0);
    
    //generate 3 local versions
    rvv0.getNextVersion();
    rvv0.getNextVersion();
    rvv0.getNextVersion();
    
    //record some version from a remote member
    rvv0.recordVersion(id1, 1);
    rvv0.recordVersion(id1, 3);
    rvv0.recordVersion(id1, 5);
    

    //Assert that the exceptions are present
    {
      Map<DiskStoreID, RegionVersionHolder<DiskStoreID>> memberToVersion = rvv0.getMemberToVersion();
      RegionVersionHolder<DiskStoreID> holder1 = memberToVersion.get(id1);

      //Make sure the exceptions are present
      assertFalse(holder1.contains(2));
      assertFalse(holder1.contains(4));
    }
    
    //Record some GC versions
    rvv0.recordGCVersion(id0, 2);
    rvv0.recordGCVersion(id1, 3);
    
    {
      Map<DiskStoreID, RegionVersionHolder<DiskStoreID>> memberToVersion = rvv0.getMemberToVersion();
      RegionVersionHolder<DiskStoreID> holder0 = memberToVersion.get(id0);
      //Make sure we didn't generate a bogus exception for
      //the local member by calling record GC version - bug 47023
      assertTrue(holder0.getExceptionForTest().isEmpty());
    }

    //Clean up old exceptions
    rvv0.pruneOldExceptions();
    
    //Make assertions about what exceptions are still present 
    Map<DiskStoreID, RegionVersionHolder<DiskStoreID>> memberToVersion = rvv0.getMemberToVersion();
    RegionVersionHolder<DiskStoreID> holder0 = memberToVersion.get(id0);
    RegionVersionHolder<DiskStoreID> holder1 = memberToVersion.get(id1);
    assertTrue(holder0.getExceptionForTest().isEmpty());
    
    //exceptions less than the GC version should have been removed
    assertTrue(holder1.contains(2));
    
    //exceptions greater than the GC version should still be there.
    assertFalse(holder1.contains(4));
    
  }

  public void testRemoveOldVersions() {
    DiskStoreID id0 = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    DiskStoreID id2 = new DiskStoreID(0, 2);
    
    DiskRegionVersionVector rvv = new DiskRegionVersionVector(id0);
    
    // generate 3 local versions
    rvv.getNextVersion();
    rvv.getNextVersion();
    rvv.getNextVersion();
    
    // record some version from a remote member
    rvv.recordVersion(id1, 1);
    rvv.recordVersion(id1, 3);
    rvv.recordVersion(id1, 5);
    // record a GC version for that member that is older than its version
    rvv.recordGCVersion(id1, 3);
    
    rvv.recordGCVersion(id2, 4950);
    rvv.recordVersion(id2, 5000);
    rvv.recordVersion(id2, 5001);
    rvv.recordVersion(id2, 5005);

    rvv.removeOldVersions();

    assertEquals("expected gc version to be set to current version for " + rvv.fullToString(), 
        rvv.getCurrentVersion(), rvv.getGCVersion(null));
    assertEquals("expected gc version to be set to current version for " + rvv.fullToString(),
        rvv.getVersionForMember(id1), rvv.getGCVersion(id1));
    assertEquals("expected gc version to be set to current version for " + rvv.fullToString(),
        rvv.getVersionForMember(id2), rvv.getGCVersion(id2));
    
    assertEquals("expected exceptions to be erased for " + rvv.fullToString(),
        rvv.getExceptionCount(id1), 0);
    assertEquals("expected exceptions to be erased for " + rvv.fullToString(),
        rvv.getExceptionCount(id2), 0);
  }


  
  public void testRegionVersionInTags() {
    VMVersionTag tag = new VMVersionTag();
    long version = 0x8080000000L;
    tag.setRegionVersion(version);
    assertEquals("failed test for bug #48576", version, tag.getRegionVersion());
  }
  
  private void doExceptionsWithContains(DiskStoreID id,
      DiskRegionVersionVector rvv) {
    rvv.recordVersion(id, 10);
    
    //Make sure we have exceptions from 0-10
    assertFalse(rvv.contains(id, 5));
    
    rvv.recordVersion(id, 5);
    assertTrue(rvv.contains(id, 5));
    assertFalse(rvv.contains(id, 4));
    assertFalse(rvv.contains(id, 6));

    for(int i =0; i < 10; i++) {
      rvv.recordVersion(id, i);
    }
    
    for(int i =0; i < 10; i++) {
      assertTrue(rvv.contains(id, i));
    }
    assertEquals(0,rvv.getExceptionCount(id));
  }

}
