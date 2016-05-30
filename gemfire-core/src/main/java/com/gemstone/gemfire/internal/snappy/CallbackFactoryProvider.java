/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.snappy;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.gemstone.gemfire.internal.cache.BucketRegion;

public abstract class CallbackFactoryProvider {

  // no-op implementation.
  private static StoreCallbacks storeCallbacks = new StoreCallbacks() {

    @Override
    public Set createCachedBatch(BucketRegion region, UUID batchID, int bucketID) {
      return null;
    }

    @Override
    public List<String> getInternalTableSchemas() {
      return Collections.emptyList();
    }

    @Override
    public int getHashCodeSnappy(Object dvd) {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public int getHashCodeSnappy(Object[] dvds) {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public boolean haveRegisteredExternalStore(String tableName) {
      return false;
    }

  };

  public static void setStoreCallbacks(StoreCallbacks cb) {
    storeCallbacks = cb;
  }

  public static StoreCallbacks getStoreCallbacks() {
    return storeCallbacks;
  }

}
