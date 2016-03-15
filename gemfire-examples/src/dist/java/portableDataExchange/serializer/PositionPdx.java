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
package portableDataExchange.serializer;

/**
 * An example of a position object for a trading application. This class
 * demonstrates serializing an object using the PdxSerializable interface.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class PositionPdx {

  private static int count = 0;

  long avg20DaysVol;
  String bondRating;
  double convRatio;
  String country;
  double delta;
  long industry;
  long issuer;
  double mktValue;
  double qty;
  String secId;
  String secLinks;
  String secType;
  int sharesOutstanding;
  String underlyer;
  long volatility;
  int pid;

  /**
   * This constructor is used by the pdx serialization framework to create the
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
