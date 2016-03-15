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

import java.io.Serializable;
import java.util.Properties;
import com.gemstone.gemfire.cache.Declarable;

/**
 * Represents a number of shares of a stock ("security") held in a {@link 
 * Portfolio}.
 * <p>
 * This class is <code>Serializable</code> because we want it to be distributed 
 * to multiple members of a distributed system.  Because this class is 
 * <code>Declarable</code>, we can describe instances of it in a GemFire 
 * <code>cache.xml</code> file.
 * <p>
 *
 * @author GemStone Systems, Inc.
 * @since 4.1.1
 */
public class Position implements Declarable, Serializable {
  
  private static final long serialVersionUID = -8229531542107983344L;
  
  private String secId;
  private double qty;
  private double mktValue;
  
  @Override
  public void init(Properties props) {
    this.secId = props.getProperty("secId");
    this.qty = Double.parseDouble(props.getProperty("qty"));
    this.mktValue = Double.parseDouble(props.getProperty("mktValue"));
  }
  
  public String getSecId(){
    return this.secId;
  }
  
  public double getQty(){
    return this.qty;
  }
    
  public double getMktValue() {
    return this.mktValue;
  }

  @Override
  public String toString(){
    return new StringBuffer()
        .append("Position [secId=").append(secId)
        .append(" qty=").append(this.qty)
        .append(" mktValue=").append(mktValue).append("]").toString();
  }
}
