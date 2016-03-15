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
package quickstart;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.FixedPartitionAttributes;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;

/**
 * This example shows basic Region operations on a fixed partitioned region, 
 * which has data partitions on two VMs. Fixed partitions "Q1" and "Q2" are 
 * defined on primary and secondary on one VM and vice-versa on other VM. The 
 * region is configured to create one extra copy of each data entry. The copies
 * are placed on different VMs, so when one VM shuts down, no data is lost. 
 * Operations proceed normally on the other VM.
 * <p>
 * Fixed partition "Q1" is associated with data of following months: Jan, Feb, Mar, Apr, May, Jun
 * <p>
 * Fixed partition "Q2" is associated with data of following months: Jul, Aug, Sep, Oct, Nov, Dec
 * <p>
 * Please refer to the quickstart guide for instructions on how to run this 
 * example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class FixedPartitionPeer1 {
  
  public static final String EXAMPLE_REGION_NAME = "exampleRegion";

  private final BufferedReader stdinReader;

  public FixedPartitionPeer1() {
    this.stdinReader = new BufferedReader(new InputStreamReader(System.in));
  }

  public static void main(String[] args) throws Exception {
    new FixedPartitionPeer1().run();
  }

  public void run() throws Exception {

    writeToStdout("This Peer defined is a partitioned region having primary partition named \"Q1\" with 6 number of buckets");
    writeToStdout("This Peer have the data for the following months: JAN, FEB, MAR, APR, MAY, JUN");
    writeNewLine();
    writeToStdout("Connecting to the distributed system and creating the cache... ");

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "FixedPartitionPeer1")
        .set("cache-xml-file", "xml/FixedPartitionPeer1.xml")
        .create();

    writeNewLine();
      
    // Get the exampleRegion
    Region<Date, String> exampleRegion = cache.getRegion(EXAMPLE_REGION_NAME);
    writeToStdout("Example region \"" + exampleRegion.getFullPath() + "\" created in cache.");

    writeNewLine();
    
    writeToStdout("Example region \"" + exampleRegion.getFullPath() + "\" is having following Fixed Partition Attributes :" );
    
    List<FixedPartitionAttributes> fpas = exampleRegion.getAttributes().getPartitionAttributes().getFixedPartitionAttributes();
    for (FixedPartitionAttributes fpa : fpas){
      writeToStdout("   "+ fpa.toString());
    }
    
    writeNewLine();
    writeToStdout("Please start other peer and then press \"Enter\" to continue.");
    stdinReader.readLine();
    
    writeToStdout("Checking size of the region, after put from other Peer");
    
    writeToStdout("exampleRegion size = " + exampleRegion.size());

    writeToStdout("Please press \"Enter\" to see local primary data");
    stdinReader.readLine();
    Region<Date, String> localPrimaryData = PartitionRegionHelper.getLocalPrimaryData(exampleRegion);
    Set<Map.Entry<Date, String>> localPrimaryDataSet = localPrimaryData.entrySet();
    writeToStdout("Key                             Value");
    for(Map.Entry<Date, String> entry : localPrimaryDataSet){
      writeToStdout(entry.getKey() + "        " + entry.getValue());
    }
    writeToStdout("Local Primary Data size = " + localPrimaryData.size());
    
    writeToStdout("Please press \"Enter\" to see local(primary as well as secondary) data");
    stdinReader.readLine();
    
    Region<Date,String> localData = PartitionRegionHelper.getLocalData(exampleRegion);
    Set<Map.Entry<Date, String>> localdataSet = localData.entrySet();
    writeToStdout("Key                             Value");
    for (Map.Entry<Date, String> entry : localdataSet) {
      writeToStdout(entry.getKey() + "        " + entry.getValue());
    }
    writeToStdout("Local Primary as well as Secondary Data size = " + localData.size());
    writeToStdout("Please check the data on other peer");
    stdinReader.readLine();
    
    writeToStdout("Please press \"Enter\" to close cache for this peer");
    stdinReader.readLine();
    
    System.out.println("Closing the cache and disconnecting.");
    cache.close();
  }

  private static void writeToStdout(String msg) {
    System.out.println(msg);
  }

  private static void writeNewLine() {
    System.out.println();
  }
}
