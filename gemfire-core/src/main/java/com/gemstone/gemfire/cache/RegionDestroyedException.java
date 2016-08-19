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
package com.gemstone.gemfire.cache;

import java.io.IOException;

/**
 * Indicates that the region has been destroyed. Further operations
 * on the region object are not allowed.
 *
 * @author Eric Zoerner
 *
 * @since 2.0
 */
public class RegionDestroyedException extends CacheRuntimeException {

  private static final long serialVersionUID = 319804842308010754L;

  private String regionFullPath;

  private transient boolean isRemote;

  /** Constructs a <code>RegionDestroyedException</code> with a message.
   * @param msg the String message
   */
  public RegionDestroyedException(String msg, String regionFullPath) {
    super(msg);
    this.regionFullPath = regionFullPath;
  }
  
  /** Constructs a <code>RegionDestroyedException</code> with a message and
   * a cause.
   * @param s the String message
   * @param ex the Throwable cause
   */
  public RegionDestroyedException(String s, String regionFullPath, Throwable ex) {
    super(s, ex);
    this.regionFullPath = regionFullPath;
  }
  
  public String getRegionFullPath() {
    return this.regionFullPath;
  }

  /**
   * Returns true if this exception originated from a remote node.
   */
  public final boolean isRemote() {
    return this.isRemote;
  }

  public void setNotRemote() {
    this.isRemote = false;
  }

  // Overrides to set "isRemote" flag after deserialization

  private synchronized void writeObject(final java.io.ObjectOutputStream out)
      throws IOException {
    getStackTrace(); // Ensure that stackTrace field is initialized.
    out.defaultWriteObject();
  }

  private void readObject(final java.io.ObjectInputStream in)
      throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    this.isRemote = true;
  }
}
