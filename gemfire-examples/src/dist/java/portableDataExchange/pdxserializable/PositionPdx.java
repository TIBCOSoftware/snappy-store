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
package portableDataExchange.pdxserializable;

import com.gemstone.gemfire.pdx.PdxReader;
import com.gemstone.gemfire.pdx.PdxSerializable;
import com.gemstone.gemfire.pdx.PdxWriter;

/**
 * An example of a position object for a trading application. This class
 * demonstrates serializing an object using the PdxSerializable interface.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class PositionPdx implements PdxSerializable {
  
  private static int count = 0;

  private long avg20DaysVol;
  private String bondRating;
  private double convRatio;
  private String country;
  private double delta;
  private long industry;
  private long issuer;
  private double mktValue;
  private double qty;
  private String secId;
  private String secLinks;
  private String secType;
  private int sharesOutstanding;
  private String underlyer;
  private long volatility;
  private int pid;

  /**
   * This contructor is used by the pdx serialization framework to create the
   * object before calling the fromData method.
   */
  public PositionPdx() {
    // do nothing
  }

  /**
   * This constructor creates a new object with some data.
   */
  public PositionPdx(String id, int shares) {
    secId = id;
    qty = shares * (count % 2 == 0 ? 10.0 : 100.0);
    mktValue = qty * 1.2345998;
    sharesOutstanding = shares;
    secType = "a";
    pid = count++;      
  }

  /**
   * The fromData method deserializes this object
   * using the given PDXReader.
   */
  @Override
  public void fromData(PdxReader reader) {
    avg20DaysVol = reader.readLong("avg20DaysVol");
    bondRating = reader.readString("bondRating");
    convRatio = reader.readDouble("convRatio");
    country = reader.readString("country");
    delta = reader.readDouble("delta");
    industry = reader.readLong("industry");
    issuer = reader.readLong("issuer");
    mktValue = reader.readDouble("mktValue");
    qty = reader.readDouble("qty");
    secId = reader.readString("secId");
    secLinks = reader.readString("secLinks");
    secType = reader.readString("secType");
    sharesOutstanding = reader.readInt("sharesOutstanding");
    underlyer = reader.readString("underlyer");
    volatility = reader.readLong("volatility");
    pid = reader.readInt("pid");
  }

  /**
   * The toData method serializes the object using a given pdx writer.
   */
  @Override
  public void toData(PdxWriter writer) {
    writer.writeLong("avg20DaysVol", avg20DaysVol);
    writer.writeString("bondRating", bondRating);
    writer.writeDouble("convRatio", convRatio);
    writer.writeString("country", country);
    writer.writeDouble("delta", delta);
    writer.writeLong("industry", industry);
    writer.writeLong("issuer", issuer);
    writer.writeDouble("mktValue", mktValue);
    writer.writeDouble("qty", qty);
    writer.writeString("secId", secId);
    writer.writeString("secLinks", secLinks);
    writer.writeString("secType", secType);
    writer.writeInt("sharesOutstanding", sharesOutstanding);
    writer.writeString("underlyer", underlyer);
    writer.writeLong("volatility", volatility);
    writer.writeInt("pid", pid);
    
    //Mark pid as an identity field.
    //objects serialized in pdx format can be read and modified while
    //remaining in serialized form. See the PdxInstance class.
    //PdxInstance objects use the identity fields in their equals and 
    //hashCode implementations.
    writer.markIdentityField("pid");
  }

  public long getAvg20DaysVol() {
    return avg20DaysVol;
  }

  public String getBondRating() {
    return bondRating;
  }

  public double getConvRatio() {
    return convRatio;
  }

  public String getCountry() {
    return country;
  }

  public double getDelta() {
    return delta;
  }

  public long getIndustry() {
    return industry;
  }

  public long getIssuer() {
    return issuer;
  }

  public double getMktValue() {
    return mktValue;
  }

  public double getQty() {
    return qty;
  }

  public String getSecId() {
    return secId;
  }

  public String getSecLinks() {
    return secLinks;
  }

  public String getSecType() {
    return secType;
  }

  public int getSharesOutstanding() {
    return sharesOutstanding;
  }

  public String getUnderlyer() {
    return underlyer;
  }

  public long getVolatility() {
    return volatility;
  }

  public int getPid() {
    return pid;
  }

  public static int getCount() {
    return count;
  }
  
  @Override
  public String toString() {
    return new StringBuilder()
        .append("Position [secId=").append(secId)
        .append(" sharesOutstanding=").append(sharesOutstanding)
        .append(" type=").append(secType)
        .append(" id=").append(pid).append("]")
        .toString();
  }  
}
