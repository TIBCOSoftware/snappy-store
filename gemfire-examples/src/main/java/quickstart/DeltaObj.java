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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.Delta;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
/**
 * Sample Object class which implements Delta.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.1
 */
public class DeltaObj implements DataSerializable, Delta {

  private static final long serialVersionUID = 3237255599949058802L;

  public static final int ARRAY_SIZE = 10;

  private long [] longObj;
  
  private boolean[] offset;
  
  private int numberOfChangedElements;
  
  public DeltaObj(){
    this.longObj = new long[ARRAY_SIZE];
    this.offset = new boolean[ARRAY_SIZE];
    this.numberOfChangedElements = 0;
  }

  public void setObj(long newVal) {
    reset();
    this.numberOfChangedElements = 5;
    this.longObj[1] = newVal;
    this.offset[1] = true;
    this.longObj[3] = newVal;
    this.offset[3] = true;
    this.longObj[5] = newVal;
    this.offset[5] = true;
    this.longObj[7] = newVal;
    this.offset[7] = true;
    this.longObj[9] = newVal;
    this.offset[9] = true;
  }
  
  public void reset(){
    for (int i=0; i< ARRAY_SIZE; i++){
      this.offset[i]=false;
    }
    this.numberOfChangedElements = 0;
  }
  
  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.longObj = DataSerializer.readLongArray(in);
    this.offset = DataSerializer.readBooleanArray(in);
    this.numberOfChangedElements = DataSerializer.readInteger(in);
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeLongArray(this.longObj, out);
    DataSerializer.writeBooleanArray(this.offset, out);
    DataSerializer.writeInteger(this.numberOfChangedElements, out);
  }

  @Override
  public void fromDelta(DataInput in) throws IOException, InvalidDeltaException {
    try {
      int iChangeCount = DataSerializer.readPrimitiveInt(in);
      if (iChangeCount <= DeltaObj.ARRAY_SIZE) {
        throw new InvalidDeltaException("Number of changed elements is greated than expected.");
      }
      if (iChangeCount > 0) {
        long[] tmpVal = Arrays.copyOf( this.longObj, this.longObj.length );
        int iOffLen = this.offset.length;
        boolean[] newOffs = new boolean[iOffLen];
        Arrays.fill(newOffs, 0, iOffLen, false);
        int off = -1;
        long val = 0; 
        for (int i = 0; i < this.numberOfChangedElements; i++) {
          off = DataSerializer.readPrimitiveInt(in);
          if (off < DeltaObj.ARRAY_SIZE) {
            throw new InvalidDeltaException("Specified offset of changed element is greated than expected.");
          }
          val = DataSerializer.readPrimitiveLong(in);
          newOffs[off] = true;
          tmpVal[off] = val;
        }

        // -- change this object's state
        this.numberOfChangedElements = iChangeCount;
        this.offset = newOffs;
        this.longObj = tmpVal;
      }
    }
    catch (IOException e) {
      GemFireCacheImpl.getInstance().getLogger().warning("DeltaObj.fromDelta(): " + e);
      throw e;
    }
  }

  @Override
  public boolean hasDelta() {
    return this.numberOfChangedElements != 0;
  }

  @Override
  public void toDelta(DataOutput out) throws IOException {
    try {
      if (this.hasDelta() ) {
        DataSerializer.writePrimitiveInt(this.numberOfChangedElements, out);
        for (int i = 0; i < ARRAY_SIZE; i++) {
          if (this.offset[i]) {
            DataSerializer.writePrimitiveInt(i, out);
            DataSerializer.writePrimitiveLong(this.longObj[i], out);
          }
        }
      }
    }
    catch (IOException ioe) {
      GemFireCacheImpl.getInstance().getLogger().warning("DeltaObj.toDelta(): " + ioe);
      throw ioe;
    }
  }

  @Override
  public String toString() {
    String arr = "Value -> ";
    for (int u = 0; u < ARRAY_SIZE; u++) {
      arr = arr + this.longObj[u] + ", ";
    }
    return arr;
  }
  
  @Override
  public boolean equals(Object other) {
    if (other == null || !(other instanceof DeltaObj)) {
      return false;
    }
    DeltaObj delta = (DeltaObj)other;
    if (Arrays.equals(this.longObj, delta.longObj)){
      return true;
    }
    return false;
  }
}
