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

import com.gemstone.gemfire.pdx.PdxReader;
import com.gemstone.gemfire.pdx.PdxSerializer;
import com.gemstone.gemfire.pdx.PdxWriter;

/**
 * An example of a PdxSerializer that can serialize PositionPdx objects.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class PositionSerializer implements PdxSerializer {

  /**
   * The fromData method deserializes this object using the given PDXReader.
   */
  @Override
  public Object fromData(Class<?> clazz, PdxReader reader) {
    if (!clazz.equals(PositionPdx.class)) {
      return null;
    }
    
    PositionPdx position = new PositionPdx();
    position.avg20DaysVol = reader.readLong("avg20DaysVol");
    position.bondRating = reader.readString("bondRating");
    position.convRatio = reader.readDouble("convRatio");
    position.country = reader.readString("country");
    position.delta = reader.readDouble("delta");
    position.industry = reader.readLong("industry");
    position.issuer = reader.readLong("issuer");
    position.mktValue = reader.readDouble("mktValue");
    position.qty = reader.readDouble("qty");
    position.secId = reader.readString("secId");
    position.secLinks = reader.readString("secLinks");
    position.secType = reader.readString("secType");
    position.sharesOutstanding = reader.readInt("sharesOutstanding");
    position.underlyer = reader.readString("underlyer");
    position.volatility = reader.readLong("volatility");
    position.pid = reader.readInt("pid");

    return position;
  }

  @Override
  public boolean toData(Object o, PdxWriter writer) {
    if (!(o instanceof PositionPdx)) {
      return false;
    }
    
    PositionPdx position = (PositionPdx) o;
    writer.writeLong("avg20DaysVol", position.avg20DaysVol);
    writer.writeString("bondRating", position.bondRating);
    writer.writeDouble("convRatio", position.convRatio);
    writer.writeString("country", position.country);
    writer.writeDouble("delta", position.delta);
    writer.writeLong("industry", position.industry);
    writer.writeLong("issuer", position.issuer);
    writer.writeDouble("mktValue", position.mktValue);
    writer.writeDouble("qty", position.qty);
    writer.writeString("secId", position.secId);
    writer.writeString("secLinks", position.secLinks);
    writer.writeString("secType", position.secType);
    writer.writeInt("sharesOutstanding", position.sharesOutstanding);
    writer.writeString("underlyer", position.underlyer);
    writer.writeLong("volatility", position.volatility);
    writer.writeInt("pid", position.pid);
    
    //Mark pid as an identity field.
    //objects serialized in pdx format can be read and modified while
    //remaining in serialized form. See the PdxInstance class.
    //PdxInstance objects use the identity fields in their equals and 
    //hashCode implementations.
    writer.markIdentityField("pid");
    
    return true;
  }
}
