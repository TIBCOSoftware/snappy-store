/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.common;

import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.SQLException;

import io.snappydata.thrift.BlobChunk;

/**
 * Enhancement to the {@link Blob} to allow obtaining the contents
 * as a {@link ByteBuffer} that can be a heap buffer or off-heap one.
 * <p>
 * This is primarily to avoid at least one copy when reading a blob over
 * socket or file where the deserialization can obtain the full contents
 * in a direct {@link ByteBuffer} that can be returned as is.
 */
public interface BufferedBlob extends Blob {

  /**
   * Get the entire contents of the {@link Blob} as a {@link BlobChunk}
   * having last==true. The underlying ByteBuffer is released once the
   * {@link BlobChunk} is written using thrift API, else caller should invoke
   * {@link BlobChunk#free()} explicitly to eagerly release any
   * direct ByteBuffers.
   */
  BlobChunk getAsLastChunk() throws SQLException;
}
