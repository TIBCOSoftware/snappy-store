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
package com.pivotal.gemfirexd.internal.engine.distributed;

public class DiskOverflowUpdateStatementDUnit extends UpdateStatementDUnit
{

  public DiskOverflowUpdateStatementDUnit(String name) {
    super(name);    
  }
  
  @Override
  public String getOverflowSuffix() {
    return  " eviction by lrucount 1 evictaction overflow synchronous ";
  }
  @Override
  public void  testBasicNodePruningWithParameter() {
    
  }
  @Override
  public void  testUpdateWithoutInsert() throws Exception {
  }
  @Override
  public void  testBug40025() throws Exception{
    
  }
  @Override
  public void  testBug40025WithOutUpdatesToPut() throws Exception {
  }
  @Override
  public void  testBug40025_1() throws Exception {
    
  }
  @Override 
  public void  testConstraintExceptionForPartitionedRegionTable_Bug40016_2() throws Exception {
  }
  @Override  
  public void  testConstraintExceptionForReplicatedRegionTable_Bug40016_1() throws Exception {
    
  }
  @Override
  public void  testBug41985() throws Exception {  
  }
  @Override
  public void  testBug41985_1() throws Exception {
    
  }
}
