/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.*;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.LogFileUtils;


public class MemberLogsMessage extends MemberExecutorMessage {

  private String memberId;
  private Long offset;
  private Long byteLength;
  private String logFileName;
  private File logDirectory;

  /** Default constructor for deserialization. Not to be invoked directly. */
  public MemberLogsMessage() {
    super(true);
  }

  public MemberLogsMessage(
      final ResultCollector<Object, Object> rc) {
    super(rc, null, false, true);
  }

  private MemberLogsMessage(final MemberLogsMessage other) {
    super(other);
    this.memberId = other.getMemberId();
    this.logFileName = other.getLogFileName();
    this.logDirectory = other.getLogDirectory();
    this.byteLength = other.getByteLength();
    this.offset = other.getOffset();
  }

  public String getMemberId() {
    return memberId;
  }

  public void setMemberId(String memberId) {
    this.memberId = memberId;
  }

  public Long getOffset() {
    return offset;
  }

  public void setOffset(Long offset) {
    this.offset = offset;
  }

  public Long getByteLength() {
    return byteLength;
  }

  public void setByteLength(Long byteLength) {
    this.byteLength = byteLength;
  }

  public String getLogFileName() {
    return logFileName;
  }

  public void setLogFileName(String logFileName) {
    this.logFileName = logFileName;
  }

  public File getLogDirectory() {
    return logDirectory;
  }

  public void setLogDirectory(File logDirectory) {
    this.logDirectory = logDirectory;
  }

  @Override
  protected void execute() throws Exception {

    GemFireCacheImpl gemFireCache = GemFireCacheImpl.getExisting();
    InternalDistributedSystem ids = gemFireCache.getDistributedSystem();
    String memberId = ids.getMemberId();

    Map logDetailsMap = new HashMap();
    logDetailsMap.put("id", memberId);
    logDetailsMap.put("name", ids.getName());
    logDetailsMap.put("userDir", this.logDirectory);
    logDetailsMap.put("logFile", this.logFileName);
    logDetailsMap.put("logData",
        LogFileUtils.getLog(this.logDirectory, this.logFileName, this.offset, this.byteLength));

    lastResult(logDetailsMap);

  }

  @Override
  public Set<DistributedMember> getMembers() {
    Set<DistributedMember> members = new HashSet<DistributedMember>();
    if(this.memberId != null || !this.memberId.isEmpty()) {
      Set<DistributedMember> distMembers = getAllGfxdMembers();
      Iterator<DistributedMember> itr = distMembers.iterator();
      while(itr.hasNext()){
        DistributedMember distMember = itr.next();
        if(distMember.getId().equalsIgnoreCase(memberId)){
          members.add(distMember);
          break;
        }
      }
    }
    return members;
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.memberId = DataSerializer.readString(in);
    this.offset = DataSerializer.readObject(in);
    this.byteLength = DataSerializer.readObject(in);
    this.logFileName = DataSerializer.readString(in);
    this.logDirectory = DataSerializer.readFile(in);
  }

  @Override
   public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(this.memberId, out);
    DataSerializer.writeObject(this.offset , out);
    DataSerializer.writeObject(this.byteLength, out);
    DataSerializer.writeString(this.logFileName, out);
    DataSerializer.writeFile(this.logDirectory, out);
  }

  @Override
  public void postExecutionCallback() {

  }

  @Override
  public boolean isHA() {
    return false;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected GfxdFunctionMessage clone() {
    return new MemberLogsMessage(this);
  }

  @Override
  public byte getGfxdID() {
    return MEMBER_LOGS_MESSAGE;
  }

}
