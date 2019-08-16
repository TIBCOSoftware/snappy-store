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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.shared.common;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This class contains all the information which the client would need to fetch
 * data in just a single hop.
 * 
 * @author kneeraj
 * 
 */
public class SingleHopInformation implements Externalizable {

  public static final int NO_SINGLE_HOP_CASE = 0;

  public static final int REPLICATE_FLAG = 1;

  public static final int LIST_RESOLVER_FLAG = 2;

  public static final int RANGE_RESOLVER_FLAG = 3;

  public static final int COLUMN_RESOLVER_FLAG = 4;

  public static final int NO_SINGLE_HOP_AS_PRIMARY_NOT_DETERMINED = 10;

  public static final int SINGLE_HOP_BUT_SERVER_FOR_SECONDARIES_NOT_DETERMINED = 11;

  public static final int NO_SINGLE_HOP_AS_COULD_NOT_DETERMINE_SERVER_LOCATION_LOGIC = 12;

  private String fullQualifiedTableName;

  private byte resolverFlag = (byte)NO_SINGLE_HOP_CASE;

  private Map mapOfListValuesToRoutingObjects;

  private int[] typeFormatIdArray;

  private ArrayList rangeValuesHolderList;

  private ArrayList bucketAndNetServerInfoList;

  private Set routingObjectInfoSet;

  private int totalNumberOfBuckets;

  private boolean requiredSerializedHash;

  private byte useOnlyPrimariesFlag = NO_CASE_FOR_ONLY_PRIMARIES;
  
  // Flags used for useOnlyPrimariesFlag - START
  private static final byte NO_CASE_FOR_ONLY_PRIMARIES = 0x0;

  private static final byte SECONDARY_SERVERS_NOT_DETERMINED_CASE = 0x1;

  private static final byte HDFS_REGION_CASE = 0x2;
  // Next will be 0x4, 0x8, ...
  // Flags used for useOnlyPrimariesFlag - END
  
  private boolean hasAggregate;

  public SingleHopInformation(String regionname) {
    this.fullQualifiedTableName = regionname;
  }

  public SingleHopInformation() {

  }

  public String getRegionName() {
    return this.fullQualifiedTableName;
  }

  public byte getResolverByte() {
    return this.resolverFlag;
  }

  public void writeExternal(ObjectOutput out) throws IOException {
    if (this.resolverFlag != NO_SINGLE_HOP_AS_PRIMARY_NOT_DETERMINED) {
      if (this.routingObjectInfoSet == null
          || this.routingObjectInfoSet.contains(ResolverUtils.TOK_ALL_NODES)) {
        this.resolverFlag = NO_SINGLE_HOP_CASE;
      }
    }
    out.writeBoolean(this.hasAggregate);
    out.writeByte(this.resolverFlag);
    if (this.resolverFlag == NO_SINGLE_HOP_CASE
        || this.resolverFlag == NO_SINGLE_HOP_AS_PRIMARY_NOT_DETERMINED) {
      return;
    }
    out.writeUTF(this.fullQualifiedTableName);
    out.writeInt(this.totalNumberOfBuckets);
    assert typeFormatIdArray != null && typeFormatIdArray.length != 0;

    // write the length of the type array first
    out.writeByte(typeFormatIdArray.length);
    for (int i = 0; i < typeFormatIdArray.length; i++) {
      out.writeShort(typeFormatIdArray[i]);
    }

    if (this.resolverFlag == LIST_RESOLVER_FLAG) {
      writeListResolverData(out);
    }

    if (this.resolverFlag == RANGE_RESOLVER_FLAG) {
      writeRangeResolverData(out);
    }

    if (this.resolverFlag == COLUMN_RESOLVER_FLAG) {
      out.writeBoolean(this.requiredSerializedHash);
      writeColumnResolverData(out);
    }

    out.writeObject(this.bucketAndNetServerInfoList);

    out.writeObject(this.routingObjectInfoSet);

    out.writeByte(this.useOnlyPrimariesFlag);
  }

  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    this.hasAggregate = in.readBoolean();
    this.resolverFlag = in.readByte();
    if (this.resolverFlag == NO_SINGLE_HOP_CASE
        || this.resolverFlag == NO_SINGLE_HOP_AS_PRIMARY_NOT_DETERMINED) {
      return;
    }
    this.fullQualifiedTableName = in.readUTF();
    this.totalNumberOfBuckets = in.readInt();
    // read the length of the type array first
    byte len = in.readByte();
    this.typeFormatIdArray = new int[len];
    for (int i = 0; i < len; i++) {
      this.typeFormatIdArray[i] = in.readShort();
    }

    if (this.resolverFlag == LIST_RESOLVER_FLAG) {
      readListResolverData(in);
    }

    if (this.resolverFlag == RANGE_RESOLVER_FLAG) {
      readRangeResolverData(in);
    }

    if (this.resolverFlag == COLUMN_RESOLVER_FLAG) {
      this.requiredSerializedHash = in.readBoolean();
      readColumnResolverData(in);
    }

    this.bucketAndNetServerInfoList = (ArrayList)in.readObject();

    this.routingObjectInfoSet = (Set)in.readObject();

    this.useOnlyPrimariesFlag = in.readByte();
  }

  private void writeColumnResolverData(ObjectOutput out) {

  }

  private void writeRangeResolverData(ObjectOutput out) throws IOException {
    assert this.rangeValuesHolderList != null;
    out.writeObject(this.rangeValuesHolderList);
  }

  private void writeListResolverData(ObjectOutput out) throws IOException {
    assert this.mapOfListValuesToRoutingObjects != null;
    out.writeObject(this.mapOfListValuesToRoutingObjects);
  }

  private void readColumnResolverData(ObjectInput in) {

  }

  private void readRangeResolverData(ObjectInput in)
      throws ClassNotFoundException, IOException {
    this.rangeValuesHolderList = (ArrayList)in.readObject();
  }

  private void readListResolverData(ObjectInput in)
      throws ClassNotFoundException, IOException {
    this.mapOfListValuesToRoutingObjects = (Map)in.readObject();
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("table name: ");
    sb.append(this.fullQualifiedTableName);
    sb.append(", resolver type: ");
    sb.append(getResolverType(this.resolverFlag));
    if (this.typeFormatIdArray != null) {
      sb.append(", type array length: ");
      sb.append(this.typeFormatIdArray.length);
      for (int i = 0; i < this.typeFormatIdArray.length; i++) {
        sb.append(", type array[" + i + "]: ");
        sb.append(this.typeFormatIdArray[i]);
      }
    }
    else {
      sb.append(", type array: " + null);
    }
    sb.append(", bucketAndNetServer list: ");
    sb.append(this.bucketAndNetServerInfoList);
    sb.append(", routing object info set: ");
    sb.append(this.routingObjectInfoSet);
    sb.append(", isHoppable: ");
    sb.append(this.isHoppable());
    sb.append(", someSecondariesNotDetermined: ");
    sb.append(this.useOnlyPrimariesFlag);
    return sb.toString();
  }

  private String getResolverType(byte flag) {
    if (flag == NO_SINGLE_HOP_CASE) {
      return "NO_SINGLE_HOP_CASE";
    }

    if (flag == REPLICATE_FLAG) {
      return "REPLICATE";
    }

    if (flag == LIST_RESOLVER_FLAG) {
      return "LIST_RESOLVER";
    }

    if (flag == RANGE_RESOLVER_FLAG) {
      return "RANGE_RESOLVER";
    }

    if (flag == COLUMN_RESOLVER_FLAG) {
      return "COLUMN_RESOLVER";
    }

    return null;
  }

  public void setResolverType(int resolverFlag) {
    this.resolverFlag = (byte)resolverFlag;
  }

  public void setMapOfListValues(Map map) {
    this.mapOfListValuesToRoutingObjects = map;
  }

  public Map getMapOfListValues() {
    return this.mapOfListValuesToRoutingObjects;
  }

  public void setTypeFormatIdArray(int[] typeFormatIdArray) {
    this.typeFormatIdArray = typeFormatIdArray;
  }

  public int[] getTypeFormatIdArray() {
    return this.typeFormatIdArray;
  }

  /**
   * a simple holder object for range specification
   * 
   * @author kneeraj
   * 
   */
  public static class PlainRangeValueHolder implements Externalizable {
    private Object lowerBound;

    private Object upperBound;

    private Integer routingValue;

    public PlainRangeValueHolder() {

    }

    public PlainRangeValueHolder(Object lowerBound, Object upperBound,
        Integer routingValue) {
      this.lowerBound = lowerBound;
      this.upperBound = upperBound;
      this.routingValue = routingValue;
    }

    public Object getLowerBound() {
      return this.lowerBound;
    }

    public Object getUpperBound() {
      return this.upperBound;
    }

    public int getRoutingVal() {
      return this.routingValue.intValue();
    }

    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeObject(this.lowerBound);
      out.writeObject(this.upperBound);
      out.writeObject(this.routingValue);
    }

    public void readExternal(ObjectInput in) throws IOException,
        ClassNotFoundException {
      this.lowerBound = in.readObject();
      this.upperBound = in.readObject();
      this.routingValue = (Integer)in.readObject();
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("PlainRangeValueHolder: lower bound= ");
      sb.append(this.lowerBound);
      sb.append(", upper bound= " + this.upperBound);
      sb.append(", routing value= ");
      sb.append(this.routingValue);
      return sb.toString();
    }
  }

  public static class BucketAndNetServerInfo implements Externalizable {
    private Integer bucketId;

    private String primaryBucketServersStr;

    private String[] secondaryBucketServersStr;

    public BucketAndNetServerInfo() {

    }

    public BucketAndNetServerInfo(Integer bucketId,
        String primaryBucketServers, String[] secondaryBucketServers) {
      this.bucketId = bucketId;
      this.primaryBucketServersStr = primaryBucketServers;
      this.secondaryBucketServersStr = secondaryBucketServers;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeInt(bucketId.intValue());
      out.writeUTF(primaryBucketServersStr);
      if (this.secondaryBucketServersStr == null) {
        out.writeShort(-1); // for null
      }
      else {
        out.writeShort(this.secondaryBucketServersStr.length);
        for (int i = 0; i < this.secondaryBucketServersStr.length; i++) {
          out.writeUTF(this.secondaryBucketServersStr[i]);
        }
      }
    }

    public void readExternal(ObjectInput in) throws IOException,
        ClassNotFoundException {
      this.bucketId = in.readInt();
      this.primaryBucketServersStr = in.readUTF();
      int secServerArrayLen = in.readShort();
      // 0 indicates no secondary server length sent from server
      if (secServerArrayLen != -1) {
        this.secondaryBucketServersStr = new String[secServerArrayLen];
        for (int i = 0; i < secServerArrayLen; i++) {
          this.secondaryBucketServersStr[i] = in.readUTF();
        }
      }
    }

    public String getPrimaryBucketServerString() {
      return this.primaryBucketServersStr;
    }

    public String[] getSecondaryServerBucketStrings() {
      return this.secondaryBucketServersStr;
    }

    public int getBucketId() {
      return this.bucketId.intValue();
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("bucket id: ");
      sb.append(this.bucketId);
      sb.append("\n");
      sb.append(", primary server string: " + this.primaryBucketServersStr);
      sb.append(", no of secondary server string: ");
      if (this.secondaryBucketServersStr != null) {
        sb.append(this.secondaryBucketServersStr.length);
        for (int i = 0; i < this.secondaryBucketServersStr.length; i++) {
          sb.append(", secondary server string [" + i + "] = ");
          sb.append(this.secondaryBucketServersStr[i]);
        }
      }
      else {
        sb.append("0");
      }
      sb.append("\n");
      return sb.toString();
    }
  }

  public void setRangeValueHolderList(ArrayList list) {
    this.rangeValuesHolderList = list;
  }

  public ArrayList getRangeValueHolderList() {
    return this.rangeValuesHolderList;
  }

  public void setBucketAndNetworkServerInfoList(ArrayList list) {
    this.bucketAndNetServerInfoList = list;
  }

  public ArrayList getBucketAndNetServerInfoList() {
    return this.bucketAndNetServerInfoList;
  }

  public static boolean isSupportedTypeForSingleHop(int typeFormatId) {
    switch (typeFormatId) {
      case StoredFormatIds.SQL_CHAR_ID:
      case StoredFormatIds.SQL_DECIMAL_ID:
      case StoredFormatIds.SQL_REAL_ID:
      case StoredFormatIds.SQL_DOUBLE_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_VARCHAR_ID:
        return true;

      default:
        return false;
    }
  }

  public void setRoutingObjectInfoSet(Set routingInfoObjects) {
    this.routingObjectInfoSet = routingInfoObjects;
  }

  public Set getRoutingObjectInfoSet() {
    return this.routingObjectInfoSet;
  }

  public boolean isHoppable() {
    if (this.resolverFlag == NO_SINGLE_HOP_CASE) {
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "SingleHopInformation::isHoppable no single hop for table: "
                + this.fullQualifiedTableName);
      }
      return false;
    }

    if (this.resolverFlag == NO_SINGLE_HOP_AS_PRIMARY_NOT_DETERMINED) {
      if (SanityManager.TraceSingleHop) {
        SanityManager
            .DEBUG_PRINT(
                SanityManager.TRACE_SINGLE_HOP,
                "SingleHopInformation::isHoppable no single hop for table: "
                    + this.fullQualifiedTableName
                    + " as servers corresponding to primaries could not be determined");
      }
      return false;
    }

    return true;
  }

  public void setTotalNumberOfBuckets(int totalNumberOfBuckets) {
    this.totalNumberOfBuckets = totalNumberOfBuckets;
  }

  public int getTotalNumberOfBuckets() {
    return this.totalNumberOfBuckets;
  }

  public void setRequiredSerializedHash(boolean requiresSerializedHash) {
    this.requiredSerializedHash = requiresSerializedHash;
  }

  public boolean getRequiresSerializedHash() {
    return this.requiredSerializedHash;
  }

  public void setSecondaryServerNotDeterminedForSomeBuckets() {
    this.useOnlyPrimariesFlag = set(this.useOnlyPrimariesFlag,
        SECONDARY_SERVERS_NOT_DETERMINED_CASE);
  }

  public boolean getAllSecondaryServersDetermined() {
    return !isSet(this.useOnlyPrimariesFlag,
        SECONDARY_SERVERS_NOT_DETERMINED_CASE);
  }

  public void setHdfsRegionCase() {
    this.useOnlyPrimariesFlag = set(this.useOnlyPrimariesFlag, HDFS_REGION_CASE);
  }

  public boolean getHdfsRegionCase() {
    return isSet(this.useOnlyPrimariesFlag, HDFS_REGION_CASE);
  }

  public void setHasAggregate() {
    this.hasAggregate = true;
  }

  public boolean getHasAggregate() {
    return this.hasAggregate;
  }
  
  public static final byte set(final byte flags, final byte mask) {
    return (byte)(flags | mask);
  }

  public static final boolean isSet(final byte flags, final byte mask) {
    return (flags & mask) != 0;
  }
}
