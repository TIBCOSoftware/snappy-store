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

package com.pivotal.gemfirexd.internal.snappy;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;

public final class LeadNodeExecutionContext implements GfxdSerializable {
  private long connId;

  public LeadNodeExecutionContext() {
    this.connId = 0;
  }

  public LeadNodeExecutionContext(long connId) {
    this.connId = connId;
  }

  public long getConnId() {
    return connId;
  }

  @Override
  public byte getGfxdID() {
    return LEAD_NODE_EXN_CTX;
  }

  @Override
  public int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    out.writeLong(connId);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    connId = in.readLong();
  }

  @Override
  public Version[] getSerializationVersions() {
    return new Version[0];
  }
}
