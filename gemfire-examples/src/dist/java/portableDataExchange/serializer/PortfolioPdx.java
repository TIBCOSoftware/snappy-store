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

import java.util.Date;
import java.util.Hashtable;
import java.util.Map;

/**
 * An example of a portfolio object for a trading application. This example 
 * object shows how to serialize and deserialize an object by implementing the 
 * PdxSerializable interface.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class PortfolioPdx {

  private static final String[] SEC_IDS = { "SUN", "IBM", "YHOO", "GOOG", "MSFT",
      "AOL", "APPL", "ORCL", "SAP", "DELL" };

  int id;
  String pkid;
  PositionPdx position1;
  PositionPdx position2;
  Map<String, PositionPdx> positions;
  String type;
  String status;
  String[] names;
  byte[] newVal;
  Date creationDate;
  byte[] arrayZeroSize;
  byte[] arrayNull;

  /**
   * This constructor is used by the pdx serialization framework to create
   * this object before calling fromData.
   */
  public PortfolioPdx() {
    // do nothing
  }

  public PortfolioPdx(int id) {
    this(id, 0);
  }

  public PortfolioPdx(int id, int size) {
    this(id, size, null);
  }

  public PortfolioPdx(int id, int size, String[] names) {
    this.names = names;
    this.id = id;
    pkid = Integer.toString(id);
    status = (id % 2 == 0) ? "active" : "inactive";
    type = "type" + (id % 3);
    int numSecIds = SEC_IDS.length;
    position1 = new PositionPdx(SEC_IDS[PositionPdx.getCount() % numSecIds], PositionPdx.getCount() * 1000);
    if (id % 2 != 0) {
      position2 = new PositionPdx(SEC_IDS[PositionPdx.getCount() % numSecIds],
        PositionPdx.getCount() * 1000);
    } 
    else {
      position2 = null;
    }
    positions = new Hashtable<String, PositionPdx>();
    positions.put(SEC_IDS[PositionPdx.getCount() % numSecIds], position1);
    if (size > 0) {
      newVal = new byte[size];
      for (int index = 0; index < size; index++) {
        newVal[index] = (byte)'B';
      }
    }
    creationDate = new Date();
    arrayNull = null;
    arrayZeroSize = new byte[0];
  }
  
  @Override
  public String toString() {
    return new StringBuilder()
        .append(String.format("Portfolio [ID={0} status={1} type={2} pkid={3}]", id, status, type, pkid))
        .append(String.format("{0}\tP1: {1}\n", position1))
        .append(String.format("{0}\tP2: {1}\n", position2))
        .append(String.format("{0}Creation Date: {1}\n", creationDate))
        .toString();
  }
}
