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
package com.gemstone.gemfire.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.distributed.internal.membership.InternalRole;
import junit.framework.TestCase;

/** Tests the subclasses of RoleException to make sure they are Serializable */
public class RoleExceptionJUnitTest extends TestCase {
  
  public RoleExceptionJUnitTest(String name) {
    super(name);
  }

//  public RoleExceptionJUnitTest() {
    // TODO Auto-generated constructor stub
//  }

  protected void setUp() throws Exception {
    super.setUp();
  }

  protected void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * Assert that RegionAccessException is serializable.
   */
  public void testRegionAccessExceptionIsSerializable() throws Exception {
    RegionAccessException out = createRegionAccessException();
    ByteArrayOutputStream baos = new ByteArrayOutputStream(100);
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(out);
    
    byte[] data = baos.toByteArray();
    
    ByteArrayInputStream bais = new ByteArrayInputStream(data);
    ObjectInputStream ois = new ObjectInputStream(bais);
    RegionAccessException in = (RegionAccessException) ois.readObject();
    assertEquals(createSetOfRoles(), in.getMissingRoles());
    
    assertEquals(out.getMessage(), in.getMessage());
    assertEquals(out.getRegionFullPath(), in.getRegionFullPath());
  }

  /**
   * Assert that RegionDistributionException is serializable.
   */
  public void testRegionDistributionExceptionIsSerializable() throws Exception {
    RegionDistributionException out = createRegionDistributionException();
    ByteArrayOutputStream baos = new ByteArrayOutputStream(100);
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(out);
    
    byte[] data = baos.toByteArray();
    
    ByteArrayInputStream bais = new ByteArrayInputStream(data);
    ObjectInputStream ois = new ObjectInputStream(bais);
    RegionDistributionException in = (RegionDistributionException) ois.readObject();
    assertEquals(createSetOfRoles(), in.getFailedRoles());

    assertEquals(out.getMessage(), in.getMessage());
    assertEquals(out.getRegionFullPath(), in.getRegionFullPath());
  }

  private Set createSetOfRoles() {
    Set set = new HashSet();
    set.add(InternalRole.getRole("RoleA"));
    set.add(InternalRole.getRole("RoleB"));
    set.add(InternalRole.getRole("RoleC"));
    set.add(InternalRole.getRole("RoleD"));
    return set;
  }
  
  private RegionAccessException createRegionAccessException() {
    String s = "MyString";
    String regionFullPath = "MyPath"; 
    Set missingRoles = createSetOfRoles();
    return new RegionAccessException(s, regionFullPath, missingRoles);
  }
  
  private RegionDistributionException createRegionDistributionException() {
    String s = "MyString";
    String regionFullPath = "MyPath"; 
    Set missingRoles = createSetOfRoles();
    return new RegionDistributionException(s, regionFullPath, missingRoles);
  }
  
}

