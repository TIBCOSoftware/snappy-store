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
package com.gemstone.gemfire.distributed.internal.deadlock;

import java.io.Serializable;
import java.lang.management.LockInfo;

/**
 * This class is serializable version of the java 1.6 lock info
 * class. It also holds a locality field to identify the VM
 * where the lock is held.
 * 
 * @author dsmith
 *
 */
class LocalLockInfo implements Serializable {
  private static final long serialVersionUID = 1L;
  
  private final Serializable locatility;
  private final LockInfo info;
  
  public LocalLockInfo(Serializable locatility, LockInfo sync) {
    super();
    this.locatility = locatility;
    this.info = sync;
  }

  public Serializable getLocality() {
    return locatility;
  }

  public LockInfo getInfo() {
    return info;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((info == null) ? 0 : info.getClassName().hashCode());
    result = prime * result + ((info == null) ? 0 : info.getIdentityHashCode());
    result = prime * result
        + ((locatility == null) ? 0 : locatility.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (!(obj instanceof LocalLockInfo))
      return false;
    LocalLockInfo other = (LocalLockInfo) obj;
    if (info == null) {
      if (other.info != null)
        return false;
    } else if (!info.getClassName().equals(other.info.getClassName())) {
      return false;
    } else if (info.getIdentityHashCode() != other.info.getIdentityHashCode()) {
      return false;
    }
    if (locatility == null) {
      if (other.locatility != null)
        return false;
    } else if (!locatility.equals(other.locatility))
      return false;
    return true;
  }
  
  @Override
  public String toString() {
    return locatility + ":" + info; 
    
  }
}