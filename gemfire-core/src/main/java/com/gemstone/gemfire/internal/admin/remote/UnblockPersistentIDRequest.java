package com.gemstone.gemfire.internal.admin.remote;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberManager;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;

  public class UnblockPersistentIDRequest  extends AdminRequest {
    PersistentMemberPattern pattern;

    public UnblockPersistentIDRequest() {

    }

    public UnblockPersistentIDRequest(PersistentMemberPattern pattern) {
      this.pattern = pattern;
    }

    public static void send(DM dm, PersistentMemberPattern pattern) {
      Set recipients = dm.getOtherDistributionManagerIds();
      UnblockPersistentIDRequest request = new UnblockPersistentIDRequest(pattern);
      request.setRecipients(recipients);

      AdminMultipleReplyProcessor replyProcessor = new AdminMultipleReplyProcessor(dm, recipients);
      request.msgId = replyProcessor.getProcessorId();
      dm.putOutgoing(request);
      try {
        replyProcessor.waitForReplies();
      } catch (ReplyException e) {
        if(e.getCause() instanceof CancelException) {
          //ignore
          return;
        }
        e.handleAsUnexpected();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      request.createResponse((DistributionManager)dm);
    }

    @Override
    protected AdminResponse createResponse(DistributionManager dm) {
      GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if(cache != null && !cache.isClosed()) {
        PersistentMemberManager mm = cache.getPersistentMemberManager();
        mm.unblockMemberForPattern(pattern);
      }

      return new UnblockPersistentIDResponse(this.getSender());
    }

    public int getDSFID() {
      return UNBLOCK_PERSISTENT_ID_REQUEST;
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      pattern = new PersistentMemberPattern();
      InternalDataSerializer.invokeFromData(pattern, in);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      InternalDataSerializer.invokeToData(pattern, out);
    }

  }

