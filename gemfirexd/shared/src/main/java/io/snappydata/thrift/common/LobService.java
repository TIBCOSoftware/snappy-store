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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;

import io.snappydata.thrift.BlobChunk;
import io.snappydata.thrift.ClobChunk;

/**
 * Interface for getting LOB chunks from remote server.
 */
public interface LobService {

  Blob createBlob(BlobChunk firstChunk, boolean forStream) throws SQLException;

  Blob createBlob(InputStream stream, long length) throws SQLException;

  Clob createClob(ClobChunk firstChunk, boolean forStream) throws SQLException;

  Clob createClob(Reader reader, long length) throws SQLException;

  Clob createClob(InputStream asciiStream, long length) throws SQLException;

  BlobChunk getBlobChunk(long lobId, long offset, int chunkSize,
      boolean freeLobAtEnd) throws SQLException;

  ClobChunk getClobChunk(long lobId, long offset, int chunkSize,
      boolean freeLobAtEnd) throws SQLException;
}
