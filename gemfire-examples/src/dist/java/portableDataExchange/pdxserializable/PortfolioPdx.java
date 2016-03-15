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


import java.util.Date;
import java.util.Hashtable;
import java.util.Map;

import com.gemstone.gemfire.pdx.PdxReader;
import com.gemstone.gemfire.pdx.PdxSerializable;
import com.gemstone.gemfire.pdx.PdxWriter;

/**
 * An example of a portfolio object for a trading application. This example 
 * object shows how to serialize and deserialize an object by implementing the 
 * PdxSerializable interface.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class PortfolioPdx implements PdxSerializable {
  
  private static final String[] SEC_IDS = { "SUN", "IBM", "YHOO", "GOOG", "MSFT",
    "AOL", "APPL", "ORCL", "SAP", "DELL" };

  private int id;
  private String pkid;
  private PositionPdx position1;
  private PositionPdx position2;
  private Map<String, PositionPdx> positions;
  private String type;
  private String status;
  private String[] names;
  private byte[] newVal;
  private Date creationDate;
  private byte[] arrayZeroSize;
  private byte[] arrayNull;

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
    position1 = new PositionPdx(SEC_IDS[PositionPdx.getCount() % numSecIds],
      PositionPdx.getCount() * 1000);
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
  public void fromData(PdxReader reader) {
    id = reader.readInt("id");

    boolean isIdentity =  reader.isIdentityField("id");

    if (isIdentity == false) {
      throw new IllegalStateException("Portfolio id is identity field");
    }

    boolean isId = reader.hasField("id");

    if (isId == false) {
      throw new IllegalStateException("Portfolio id field not found");
    }

    boolean isNotId = reader.hasField("ID");

    if (isNotId == true) {
      throw new IllegalStateException("Portfolio isNotId field found");
    }

    pkid = reader.readString("pkid");
    position1 = (PositionPdx)reader.readObject("position1");
    position2 = (PositionPdx)reader.readObject("position2");
    positions = (Map<String, PositionPdx>) reader.readObject("positions");
    type = reader.readString("type");
    status = reader.readString("status");
    names = reader.readStringArray("names");
    newVal = reader.readByteArray("newVal");
    creationDate = reader.readDate("creationDate");
    arrayNull = reader.readByteArray("arrayNull");
    arrayZeroSize = reader.readByteArray("arrayZeroSize");
  }

  @Override
  public void toData(PdxWriter writer) {
    writer.writeInt("id", id)
        .markIdentityField("id") //identity field
        .writeString("pkid", pkid)
        .writeObject("position1", position1)
        .writeObject("position2", position2)
        .writeObject("positions", positions)
        .writeString("type", type)
        .writeString("status", status)
        .writeStringArray("names", names)
        .writeByteArray("newVal", newVal)
        .writeDate("creationDate", creationDate)
        .writeByteArray("arrayNull", arrayNull)
        .writeByteArray("arrayZeroSize", arrayZeroSize);
  }
  
  @Override
  public String toString() {
    return new StringBuilder()
        .append(String.format("Portfolio [ID=%d status=%s type=%s pkid=%s]", id, status, type, pkid))
        .append(String.format("\tP1: %s\n", position1))
        .append(String.format("\tP2: %s\n", position2))
        .append(String.format("Creation Date: %s\n", creationDate))
        .toString();
  }
}
