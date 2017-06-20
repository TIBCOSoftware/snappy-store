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
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
public class FixedPartitionPeer2 {
  
  public static final String EXAMPLE_REGION_NAME = "exampleRegion";

  private final BufferedReader stdinReader;
  
  public enum Months {
    JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC
  };

  public FixedPartitionPeer2() {
    this.stdinReader = new BufferedReader(new InputStreamReader(System.in));
  }

  public static void main(String[] args) throws Exception {
    new FixedPartitionPeer2().run();
  }

  public void run() throws Exception {
    
    writeToStdout("This Peer defined is a partitioned region having primary partition named \"Q2\" with 6 number of buckets");
    writeToStdout("This Peer have the data for the following months : JUL, AUG, SEP, OCT, NOV, DEC");
    writeNewLine();
    writeToStdout("Connecting to the distributed system and creating the cache... ");

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "FixedPartitionPeer2")
        .set("cache-xml-file", "xml/FixedPartitionPeer2.xml")
        .create();

    writeNewLine();
      
    // Get the exampleRegion
    Region<Date, String> exampleRegion = cache.getRegion(EXAMPLE_REGION_NAME);
    writeToStdout("Example region \"" + exampleRegion.getFullPath()+ "\" created in cache.");

    writeNewLine();
    
    writeToStdout("Example region \"" + exampleRegion.getFullPath()
        + "\" is having following Fixed Partition Attributes :" );
    
    List<FixedPartitionAttributes> fpas = exampleRegion.getAttributes().getPartitionAttributes().getFixedPartitionAttributes();
    
    for (FixedPartitionAttributes fpa : fpas){
      writeToStdout("   "+ fpa.toString());
    }
    writeNewLine();
    
    writeToStdout("Entering data to exampleRegion");
    writeToStdout("Please press \"Enter\" to continue.");
    stdinReader.readLine();
    writeToStdout("Key                                           Value");
    for (Months month : Months.values()) {
      for (int i = 1; i < 3; i++) {
        Date date = generateDate(i, month.toString());
        String value = month.toString() + i;
        writeToStdout(date+"                             "+value);
        writeToStdout("");
        exampleRegion.put(date, value);
      }
    }
    
    writeToStdout("exampleRegion size = " + exampleRegion.size());
    
    writeToStdout("Check size of the region on other peer and then press \"Enter\"");
    stdinReader.readLine();
    
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
    Region<Date, String> localData = PartitionRegionHelper.getLocalData(exampleRegion);
    Set<Map.Entry<Date, String>> localdataSet = localData.entrySet();
    writeToStdout("Key                             Value");
    for(Map.Entry<Date, String> entry : localdataSet){
      writeToStdout(entry.getKey() + "        " + entry.getValue());
    }
    writeToStdout("Local Primary as well as Secondary Data size = " + localData.size());
    writeToStdout("Please close the cache on other peer");
    stdinReader.readLine();
    
    writeToStdout("Now please press \"Enter\" to see local primary data");
    stdinReader.readLine();
    localPrimaryData = PartitionRegionHelper.getLocalPrimaryData(exampleRegion);
    localPrimaryDataSet = localPrimaryData.entrySet();
    writeToStdout("Key                             Value");
    for(Map.Entry<Date, String> entry : localPrimaryDataSet){
      writeToStdout(entry.getKey() + "        " + entry.getValue());
    }
    writeToStdout("Local Primary Data size = " + localPrimaryData.size());
    
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

  private static Date generateDate(int i, String month) {
    String year = "2010";
    String day = null;
    if (i > 0 && i < 10) {
      day = "0" + i;
    }
    else {
      day = new Integer(i).toString();
    }
    String dateString = day + "-" + month + "-" + year;
    String DATE_FORMAT = "dd-MMM-yyyy";
    SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
    Date date = null;
    try {
      date = sdf.parse(dateString);
    }
    catch (ParseException e) {
      e.printStackTrace();
    }
    return date;
  }
}
