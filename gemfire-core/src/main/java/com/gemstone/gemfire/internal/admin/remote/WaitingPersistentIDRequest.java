package com.gemstone.gemfire.internal.admin.remote;

import java.io.DataInput;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberManager;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

public class WaitingPersistentIDRequest extends AdminRequest {

  public static Set<PersistentID> send(DM dm) {
    Set recipients = dm.getOtherDistributionManagerIds();

    WaitingPersistentIDRequest request = new WaitingPersistentIDRequest();

    request.setRecipients(recipients);

    WaitingPersistentIDProcessor replyProcessor = new WaitingPersistentIDProcessor(dm, recipients);
    request.msgId = replyProcessor.getProcessorId();
    dm.putOutgoing(request);
    try {
      replyProcessor.waitForReplies();
    } catch (ReplyException e) {
      if (!(e.getCause() instanceof CancelException)) {
        e.handleAsUnexpected();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    Set<PersistentID> results = replyProcessor.waiting;

    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    cache.getLoggerI18n().info(LocalizedStrings.DEBUG, "The waitign memberids are " + results);

    WaitingPersistentIDsResponse localResponse = (WaitingPersistentIDsResponse)request.createResponse((DistributionManager)dm);
    results.addAll(localResponse.getWaitingIds());

    return results;
  }

  @Override
  protected AdminResponse createResponse(DistributionManager dm) {

    Set<PersistentID> waitingIds = new HashSet<PersistentID>();
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache != null && !cache.isClosed()) {
      PersistentMemberManager mm = cache.getPersistentMemberManager();
      Set<PersistentMemberID> waitingMemberIds = mm.getWaitingIds();
      cache.getLoggerI18n().info(LocalizedStrings.DEBUG, "The waitign memberids are " + waitingMemberIds);
      for (PersistentMemberID id : waitingMemberIds) {
        waitingIds.add(new PersistentMemberPattern(id));
      }
    }
    return new WaitingPersistentIDsResponse(waitingIds, this.getSender());
  }


  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    // TODO Auto-generated method stub
    return super.clone();
  }

  public int getDSFID() {
    return WAITING_PERSISTENT_IDS_REQUEST;
  }

  private static class WaitingPersistentIDProcessor extends AdminMultipleReplyProcessor {
    Set<PersistentID> waiting = Collections.synchronizedSet(new TreeSet<PersistentID>());

    /**
     * @param dm
     * @param recipients
     */
    public WaitingPersistentIDProcessor(DM dm, Set recipients) {
      super(dm, recipients);
    }

    @Override
    protected void process(DistributionMessage msg, boolean warn) {
      if (msg instanceof WaitingPersistentIDsResponse) {
        waiting.addAll(((WaitingPersistentIDsResponse)msg).getWaitingIds());
      }
      super.process(msg, warn);
    }
  }
}

