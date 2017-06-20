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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.Properties;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;

public final class SnappyRemoveCachedObjectsFunction
    implements Function, Declarable {

  public final static String ID = "snappy-SnappyRemoveCachedObjectsFunction";

  @Override
  public void init(Properties props) {
    // nothing required for this function
  }

  @Override
  public boolean hasResult() {
    return false;
  }

  @Override
  public void execute(FunctionContext context) {
    SnappyRemoveCachedObjectsFunctionArgs args =
        (SnappyRemoveCachedObjectsFunctionArgs)context.getArguments();
    CallbackFactoryProvider.getStoreCallbacks().
        cleanUpCachedObjects(args.table, args.sentFromExternalCluster );
  }

  @Override
  public String getId() {
    return SnappyRemoveCachedObjectsFunction.ID;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return false;
  }

  public static
  SnappyRemoveCachedObjectsFunctionArgs newArgs(String table,
      Boolean sentFromExternalCluster) {
    return new SnappyRemoveCachedObjectsFunctionArgs(table,
        sentFromExternalCluster);
  }

  public final static class SnappyRemoveCachedObjectsFunctionArgs
      extends GfxdDataSerializable implements Serializable {
    String table;
    Boolean sentFromExternalCluster;

    public SnappyRemoveCachedObjectsFunctionArgs() {
    }

    public SnappyRemoveCachedObjectsFunctionArgs(String table, Boolean
        sentFromExternalCluster) {
      this.table = table;
      this.sentFromExternalCluster = sentFromExternalCluster;
    }

    @Override
    public byte getGfxdID() {
      return SNAPPY_REMOVE_CACHED_OBJECTS_ARGS;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      InternalDataSerializer.writeString(this.table, out);
      InternalDataSerializer.writeBoolean(this.sentFromExternalCluster, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      this.table = DataSerializer.readString(in);
      this.sentFromExternalCluster = in.readBoolean();
    }
  }

}
