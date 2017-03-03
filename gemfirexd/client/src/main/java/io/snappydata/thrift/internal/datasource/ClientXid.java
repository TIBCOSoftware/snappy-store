/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
package io.snappydata.thrift.internal.datasource;

import javax.transaction.xa.Xid;

import io.snappydata.thrift.TransactionXid;

/**
 * Wraps an immutable {@link TransactionXid}.
 */
public class ClientXid implements Xid {

  private final int formatId;
  private final byte[] globalId;
  private final byte[] branchQualifier;

  /**
   * Wrapper for an immutable {@link TransactionXid}.
   */
  public ClientXid(TransactionXid xid) {
    this.formatId = xid.getFormatId();
    this.globalId = xid.getGlobalId();
    this.branchQualifier = xid.getBranchQualifier();
  }

  @Override
  public int getFormatId() {
    return this.formatId;
  }

  @Override
  public byte[] getGlobalTransactionId() {
    return this.globalId;
  }

  @Override
  public byte[] getBranchQualifier() {
    return this.branchQualifier;
  }
}
