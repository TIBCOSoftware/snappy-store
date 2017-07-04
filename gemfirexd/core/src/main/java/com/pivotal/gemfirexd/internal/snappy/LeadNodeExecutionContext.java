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
import java.util.Properties;

import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;

public final class LeadNodeExecutionContext implements GfxdSerializable {
  private long connId;
  private Properties connProps;

  public LeadNodeExecutionContext() {
    this.connId = 0;
    this.connProps = new Properties();
  }

  public LeadNodeExecutionContext(long connId) {
    this.connId = connId;
    this.connProps = new Properties();
  }

  public LeadNodeExecutionContext(long connId, Properties connProps) {
    this.connId = connId;
    this.connProps = connProps == null ? new Properties() : connProps;
  }

  public long getConnId() {
    return connId;
  }

  public Properties getConnProps() {
    return connProps;
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
    if (this.connProps.isEmpty()) {
      out.writeUTF("");
      out.writeUTF("");
    } else {
      String p = this.connProps.getProperty(Attribute.USERNAME_ATTR);
      if (p != null) {
        out.writeUTF(p);
        out.writeUTF(this.connProps.getProperty(Attribute.PASSWORD_ATTR));
      } else {
        out.writeUTF("");
        out.writeUTF("");
      }
    }
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    connId = in.readLong();
    this.connProps.setProperty(Attribute.USERNAME_ATTR, in.readUTF());
    this.connProps.setProperty(Attribute.PASSWORD_ATTR, in.readUTF());
    Misc.getI18NLogWriter().info(LocalizedStrings.DEBUG, "ABS received credentials at lead " + this
        .connProps.getProperty(Attribute
        .USERNAME_ATTR) + ",  " + this.connProps.getProperty(Attribute.PASSWORD_ATTR));
  }

  @Override
  public Version[] getSerializationVersions() {
    return new Version[0];
  }
}
