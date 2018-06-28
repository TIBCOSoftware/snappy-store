package com.gemstone.gemfire.internal.admin.remote;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;

public class UnblockPersistentIDResponse extends AdminResponse {
  public UnblockPersistentIDResponse() {
  }

  public UnblockPersistentIDResponse(InternalDistributedMember sender) {
    this.setRecipient(sender);
  }

  public int getDSFID() {
    return UNBLOCK_PERSISTENT_ID_RESPONSE;
  }
}