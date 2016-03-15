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
 * An example of a PdxSerializer. This serializer handles PortfolioPdx and
 * PositionPdx objects by delegating to serializers for those individual object
 * types.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class ExamplePdxSerializer implements PdxSerializer {
  
  private final PdxSerializer portfolioSerializer = new PortfolioSerializer();
  private final PdxSerializer positionSerializer = new PositionSerializer();

  /**
   * Serialize the given object using the PdxWriter if possible.
   */
  @Override
  public boolean toData(Object o, PdxWriter out) {
    //handle PortfolioPdx objects
    if (o instanceof PortfolioPdx) {
      return portfolioSerializer.toData(o, out);
    }
    
    //handle PositionPdx objects
    if (o instanceof PositionPdx) {
      return positionSerializer.toData(o, out);
    }
    
    //If the object type is something we don't understand, return false
    //to allow the serialization framework to try to serialize the object
    //using java serialization
    return false;
  }

  @Override
  public Object fromData(Class<?> clazz, PdxReader in) {
    //handle PortfolioPdx objects
    if (clazz.equals(PortfolioPdx.class)) {
      return portfolioSerializer.fromData(clazz, in);
    }
    
    //handle PositionPdx objects
    if (clazz.equals(PositionPdx.class)) {
      return positionSerializer.fromData(clazz, in);
    }

    //If the object type is something we don't understand, return null
    return null;
  }
}
