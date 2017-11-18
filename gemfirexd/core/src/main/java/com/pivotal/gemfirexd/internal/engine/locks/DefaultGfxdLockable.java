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

package com.pivotal.gemfirexd.internal.engine.locks;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 * A default implementation of {@link GfxdLockable} that can be used to create
 * an {@link GfxdLockable} from a given object that is used as the
 * {@link #getName()} method.
 *
 * @author swale
 * @since 6.5
 */
public final class DefaultGfxdLockable extends AbstractGfxdLockable
    implements DataSerializable {

  private Object name;

  private transient final String traceFlag;

  public DefaultGfxdLockable(Object name, String traceFlag) {
    this.name = name;
    this.traceFlag = traceFlag;
    setTraceLock();
  }

  @Override
  public Object getName() {
    return this.name;
  }

  @Override
  protected boolean traceThisLock() {
    return this.traceFlag != null && SanityManager.TRACE_ON(this.traceFlag);
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeObject(this.name, out);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.name = DataSerializer.readObject(in);
  }

  @Override
  public int hashCode() {
    return this.name.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof DefaultGfxdLockable) {
      DefaultGfxdLockable otherLockable = (DefaultGfxdLockable)other;
      return this.name.equals(otherLockable.name);
    }
    return false;
  }

  @Override
  public String toString() {
    return "DefaultGfxdLockable@"
        + Integer.toHexString(System.identityHashCode(this)) + ':'
        + this.name.toString();
  }
}
