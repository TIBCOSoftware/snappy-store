/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.shared;

/**
 * Request sent to {@link ByteBufferReference#getValueRetain}.
 */
public enum FetchRequest {
  /**
   * Return with original form of buffer.
   */
  ORIGINAL,
  /**
   * Return decompressed buffer and store in region if possible.
   */
  DECOMPRESS,
  /**
   * Return compressed buffer and store in region if required.
   */
  COMPRESS,
  /**
   * Return decompressed buffer only if decompressed form can be stored
   * in memory (i.e. don't expend effort to decompress multiple times for
   * every fetch) else return null if need to read from disk. Typically
   * must be followed by a call with {@link #ORIGINAL} in case result is null.
   */
  DECOMPRESS_IF_IN_MEMORY,
}
