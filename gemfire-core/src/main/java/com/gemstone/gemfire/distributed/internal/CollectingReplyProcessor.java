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
package com.gemstone.gemfire.distributed.internal;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * This reply processor collects all of the exceptions/results from the
 * ReplyMessages it receives
 * 
 * @author bruces
 *
 */
public class CollectingReplyProcessor<T> extends ReplyProcessor21 {

  private Map<InternalDistributedMember, T> results = new ConcurrentHashMap<>();

  public CollectingReplyProcessor(DM dm,
      Collection initMembers) {
    super(dm, initMembers);
  }
  
  @Override
  protected void process(DistributionMessage msg, boolean warn) {
    if (msg instanceof ReplyMessage) {
      InternalDistributedSystem.getLoggerI18n().info(LocalizedStrings.DEBUG,
          "processing message with return value " + ((ReplyMessage)msg).getReturnValue());
      results.put(msg.getSender(), (T)((ReplyMessage)msg).getReturnValue());
    }
    super.process(msg, warn);
  }
  
  public Map<InternalDistributedMember, T> getResults() {
    return this.results;
  }

}
