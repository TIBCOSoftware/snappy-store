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

import java.util.Properties;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.util.CacheListenerAdapter;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;

/**
 * Delta Propagation quick start receiver cache listener
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.1
 */
public class DeltaReceiverListener extends CacheListenerAdapter<Object, Object> implements Declarable {
  
  private static long lastUpdateTime;
  
  private static final int FEED_CYCLES = 5;
  private static final int PUT_KEY_RANGE = 2;
  
  /**
   * Processes an afterCreate event.
   * 
   * @param event The afterCreate <code>EntryEvent</code> received
   */
  @Override
  public void afterCreate(EntryEvent<Object, Object> event) {
    if (event.getKey().equals("LAST_KEY")) {
      Long starttime = (Long)event.getNewValue();
      GemFireCacheImpl.getInstance().getLogger().fine(
          "Avg time taken for " + FEED_CYCLES * PUT_KEY_RANGE + " operations: "
          + (lastUpdateTime - starttime.longValue()) / (FEED_CYCLES * PUT_KEY_RANGE) +"ms");
    }
    processEvent("afterCreate", event);
  }

  /**
   * Processes an afterUpdate event.
   * 
   * @param event The afterUpdate <code>EntryEvent</code> received
   */
  @Override
  public void afterUpdate(EntryEvent<Object, Object> event) {
    lastUpdateTime = System.currentTimeMillis();
    processEvent("afterUpdate", event);
  }

  protected void processEvent(String operation, EntryEvent<Object, Object> event) {
    if (!event.getKey().equals("LAST_KEY"))
      System.out.println("ServerListener received " + operation + " Region : "
          + event.getRegion().getName() + ": " + event.getKey() + "->"
          + ((DeltaObj)event.getNewValue()).toString());
  }

  @Override
  public void init(Properties props) {
    // do nothing
  }
}
