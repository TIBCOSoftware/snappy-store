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

import com.gemstone.gemfire.cache.RegionEvent;

/**
 * CacheListener for the durable client.
 * <p>
 *
 * @author GemStone Systems, Inc.
 */
public class DurableClientCacheListener<K,V> extends SimpleCacheListener<K,V> {

  @Override
  public void afterRegionLive(RegionEvent<K,V> e) {
    System.out.println("    Received afterRegionLive event, sent to durable clients after the server has finished replaying stored events.");
  }
}
