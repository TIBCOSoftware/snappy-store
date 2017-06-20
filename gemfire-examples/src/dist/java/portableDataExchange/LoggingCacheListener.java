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
package portableDataExchange;

import java.util.Date;
import java.util.Properties;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.util.CacheListenerAdapter;
import com.gemstone.gemfire.pdx.PdxInstance;

/**
 * An example of a cache listener that does not deserialize objects stored in 
 * the PDX format. Instead, it introspects the serialized version of the object.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class LoggingCacheListener extends CacheListenerAdapter<String, PdxInstance> implements Declarable {

  @Override
  public void afterCreate(EntryEvent<String, PdxInstance> event) {
    //The new value will be a PdxInstance, because the cache
    //has read-serialized set to true in server.xml.
    //PdxInstance is a wrapper around the serialized object.
    PdxInstance portfolio = event.getNewValue();
    
    Integer id = (Integer) portfolio.getField("id");
    Date creationDate = (Date) portfolio.getField("creationDate");

    //print out the id and creation time of the portfolio.
    System.out.println("LoggingCacheListener: - " + id + " created at " + creationDate);
  }

  /**
   * Initialize any properties in the specified in the cache.xml file.
   */
  @Override
  public void init(Properties props) {
    // do nothing
  }
}
