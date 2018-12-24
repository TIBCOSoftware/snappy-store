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

package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.DVDIOUtil;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.SnappyResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;
import com.pivotal.gemfirexd.internal.snappy.SparkSQLExecute;
import org.apache.log4j.Logger;

/**
 * Route query to Snappy Spark Lead node.
 * <p/>
 */
public final class LeadNodeExecutorMsg extends MemberExecutorMessage<Object> {

  private String sql;
  private LeadNodeExecutionContext ctx;
  private transient SparkSQLExecute exec;
  private String schema;
  protected ParameterValueSet pvs;
  // transient members set during deserialization and used in execute
  private transient byte[] pvsData;
  private transient int[] pvsTypes;

  private transient byte leadNodeFlags;
  // possible values for leadNodeFlags
  private static final byte IS_PREPARED_STATEMENT = 0x1;
  private static final byte IS_PREPARED_PHASE = 0x2;
  private static final byte IS_UPDATE_OR_DELETE = 0x4;

  private static final Pattern PARSE_EXCEPTION = Pattern.compile(
      "(Pars[a-zA-Z]*Exception)|(Pars[a-zA-Z]*Error)");

  public LeadNodeExecutorMsg(String sql, String schema, LeadNodeExecutionContext ctx,
      GfxdResultCollector<Object> rc, ParameterValueSet inpvs, boolean isPreparedStatement,
      boolean isPreparedPhase, Boolean isUpdateOrDelete) {
    super(rc, null, false, true);
    this.schema = schema;
    this.sql = sql;
    this.ctx = ctx;
    this.pvs = inpvs;
    if (isPreparedStatement) leadNodeFlags |= IS_PREPARED_STATEMENT;
    if (isPreparedPhase) leadNodeFlags |= IS_PREPARED_PHASE;
    if (isUpdateOrDelete) leadNodeFlags |= IS_UPDATE_OR_DELETE;
  }

  /**
   * Default constructor for deserialization. Not to be invoked directly.
   */
  public LeadNodeExecutorMsg() {
    super(true);
  }

  public boolean isPreparedStatement() {
    return (leadNodeFlags & IS_PREPARED_STATEMENT) != 0;
  }

  public boolean isPreparedPhase() {
    return (leadNodeFlags & IS_PREPARED_PHASE) != 0;
  }

  public boolean isUpdateOrDelete() { return (leadNodeFlags & IS_UPDATE_OR_DELETE) != 0; }

  @Override
  public Set<DistributedMember> getMembers() {
    return Misc.getLeadNode();
  }

  @Override
  public void postExecutionCallback() {
  }

  @Override
  public boolean isHA() {
    return true;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected void execute() throws Exception {
    ClassLoader origLoader = Thread.currentThread().getContextClassLoader();
    CallbackFactoryProvider.getClusterCallbacks().setLeadClassLoader();
    try {
      if (isPreparedStatement() && !isPreparedPhase()) {
        getParams();
      }
      Logger logger = null;
      if (GemFireXDUtils.TraceQuery) {
        logger = Logger.getLogger(getClass());
        StringBuilder str = new StringBuilder();
        appendFields(str);
        logger.info("LeadNodeExecutorMsg.execute: Got sql = " + str.toString());
      }
      InternalDistributedMember m = this.getSenderForReply();
      final Version v = m.getVersionObject();
      exec = CallbackFactoryProvider.getClusterCallbacks().getSQLExecute(
          sql, schema, ctx, v, this.isPreparedStatement(), this.isPreparedPhase(), this.pvs);
      SnappyResultHolder srh = new SnappyResultHolder(exec, isUpdateOrDelete());

      srh.prepareSend(this);
      this.lastResultSent = true;
      this.endMessage();
      if (GemFireXDUtils.TraceQuery) {
        assert logger != null;
        logger.info("LeadNodeExecutorMsg.execute: Sent Last result ");
      }
    } catch (Exception ex) {
      Exception serverException = getExceptionToSendToServer(ex);
      Logger.getLogger(getClass()).warn(
          "LeadNodeExecutorMsg.execute: failed with exception: " + ex);
      throw serverException;
    } finally {
      Thread.currentThread().setContextClassLoader(origLoader);
    }
  }

  private static class SparkExceptionWrapper extends Exception {
    private static final long serialVersionUID = -4668836542769295434L;

    public SparkExceptionWrapper(Throwable ex) {
      super(ex.getClass().getName() + ": " + ex.getMessage(),
          ex.getCause() != null ? new SparkExceptionWrapper(ex.getCause()) : null);
      this.setStackTrace(ex.getStackTrace());
    }
  }

  public static Exception getExceptionToSendToServer(Exception ex) {
    // Catch all exceptions and convert so can be caught at XD side
    // Check if the exception can be serialized or not
    // Now always wrapping exception because some exception classes may be deployed
    // only on lead and may not be available on server esp. in system class loader
    boolean wrapException = true;
    /*
    HeapDataOutputStream hdos = null;
    try {
      hdos = new HeapDataOutputStream();
      DataSerializer.writeObject(ex, hdos);
    } catch (Exception e) {
      wrapException = true;
    } finally {
      if (hdos != null) {
        hdos.close();
      }
    }
    */

    Throwable cause = ex;
    Throwable sparkEx = null;
    while (cause != null) {
      if (cause instanceof StandardException || cause instanceof SQLException) {
        return (Exception)cause;
      }
      String causeName = cause.getClass().getName();
      if (causeName.contains("parboiled") || PARSE_EXCEPTION.matcher(causeName).find()) {
        return StandardException.newException(
            SQLState.LANG_SYNTAX_ERROR,
            (!wrapException ? cause : new SparkExceptionWrapper(cause)),
            cause.getMessage());
      } else if (causeName.contains("AnalysisException") ||
          causeName.contains("NoSuch") || causeName.contains("NotFound")) {
        return StandardException.newException(
            SQLState.LANG_SYNTAX_OR_ANALYSIS_EXCEPTION,
            (!wrapException ? cause : new SparkExceptionWrapper(cause)),
            cause.getMessage());
      } else if (causeName.contains("apache.spark.storage")) {
        return StandardException.newException(
            SQLState.DATA_UNEXPECTED_EXCEPTION,
            (!wrapException ? cause : new SparkExceptionWrapper(cause)),
            cause.getMessage());
      } else if (causeName.contains("apache.spark.sql")) {
        Throwable nestedCause = cause.getCause();
        while (nestedCause != null) {
          if (nestedCause.getClass().getName().contains("ErrorLimitExceededException")) {
            return StandardException.newException(
                SQLState.LANG_UNEXPECTED_USER_EXCEPTION,
                (!wrapException ? nestedCause : new SparkExceptionWrapper(
                    nestedCause)), nestedCause.getMessage());
          }
          nestedCause = nestedCause.getCause();
        }
        return StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION,
            (!wrapException ? cause : new SparkExceptionWrapper(cause)),
            cause.getMessage());
      } else if (causeName.contains("SparkException")) {
        sparkEx = cause;
      }
      cause = cause.getCause();
    }
    if (sparkEx != null) {
      return StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION,
          (!wrapException ? sparkEx : new SparkExceptionWrapper(sparkEx)),
          sparkEx.getMessage());
    }
    return ex;
  }

  @Override
  protected void executeFunction(boolean enableStreaming)
      throws StandardException, SQLException {
    try {
      super.executeFunction(enableStreaming);
    } catch (RuntimeException re) {
      throw handleLeadNodeRuntimeException(re);
    }
  }

  public static Exception handleLeadNodeException(Exception e, String sql) {
    final Exception cause;
    if (e instanceof RuntimeException) {
      cause = handleLeadNodeRuntimeException((RuntimeException)e);
    } else {
      cause = e;
    }
    return GemFireXDRuntimeException.newRuntimeException("Failure for " + sql, cause);
  }

  public static RuntimeException handleLeadNodeRuntimeException(
      RuntimeException re) {
    Throwable cause = re;
    if (re instanceof GemFireXDRuntimeException ||
        re instanceof FunctionException ||
        re instanceof FunctionExecutionException ||
        re instanceof ReplyException) {
      cause = re.getCause();
    }
    if (cause instanceof RegionDestroyedException) {
      RegionDestroyedException rde = (RegionDestroyedException)cause;
      // don't mark as remote so that no retry is done (SNAP-961)
      // a top-level exception can only have been from lead node itself
      if (rde.isRemote()) rde.setNotRemote();
    }
    if (cause instanceof DiskAccessException) {
      DiskAccessException dae = (DiskAccessException)cause;
      // don't mark as remote so that no retry is done (SNAP-961)
      // a top-level exception can only have been from lead node itself
      if (dae.isRemote()) dae.setNotRemote();
    }
    return re;
  }

  @Override
  protected LeadNodeExecutorMsg clone() {
    final LeadNodeExecutorMsg msg = new LeadNodeExecutorMsg(this.sql, this.schema, this.ctx,
        (GfxdResultCollector<Object>)this.userCollector, this.pvs, this.isPreparedStatement(),
        this.isPreparedPhase(), this.isUpdateOrDelete());
    msg.exec = this.exec;
    return msg;
  }

  @Override
  public byte getGfxdID() {
    return LEAD_NODE_EXN_MSG;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.sql = DataSerializer.readString(in);
    this.schema = DataSerializer.readString(in);
    this.ctx = DataSerializer.readObject(in);

    this.leadNodeFlags = DataSerializer.readByte(in);
    if (isPreparedStatement() && !isPreparedPhase()) {
      try {
        this.pvsTypes = DataSerializer.readIntArray(in);
        this.pvsData = DataSerializer.readByteArray(in);
      } catch (RuntimeException ex) {
        throw ex;
      }
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(this.sql, out);
    DataSerializer.writeString(this.schema , out);
    DataSerializer.writeObject(ctx, out);

    DataSerializer.writeByte(this.leadNodeFlags, out);
    if (isPreparedStatement() && !isPreparedPhase()) {
      int paramCount = this.pvs != null ? this.pvs.getParameterCount() : 0;
      final int numEightColGroups = BitSetSet.udiv8(paramCount);
      final int numPartialCols = BitSetSet.umod8(paramCount);
      try {
        // Write Types
        // TODO: See SparkSQLPreapreImpl
        if (this.pvsTypes == null) {
          this.pvsTypes = new int[paramCount * 3 + 1];
          this.pvsTypes[0] = paramCount;
          for (int i = 0; i < paramCount; i ++) {
            DataValueDescriptor dvd = this.pvs.getParameter(i);
            this.pvsTypes[i * 3 + 1] = dvd.getTypeFormatId();
            if (dvd instanceof SQLDecimal) {
              this.pvsTypes[i * 3 + 2] = ((SQLDecimal)dvd).getDecimalValuePrecision();
              this.pvsTypes[i * 3 + 3] = ((SQLDecimal)dvd).getDecimalValueScale();
            } else {
              this.pvsTypes[i * 3 + 2] = -1;
              this.pvsTypes[i * 3 + 3] = -1;
            }
          }
        }
        DataSerializer.writeIntArray(this.pvsTypes, out);

        // Write Data
        final HeapDataOutputStream hdos;
        if (paramCount > 0) {
          hdos = new HeapDataOutputStream();
          DVDIOUtil.writeParameterValueSet(this.pvs, numEightColGroups,
              numPartialCols, hdos);
          InternalDataSerializer.writeArrayLength(hdos.size(), out);
          hdos.sendTo(out);
        } else {
          InternalDataSerializer.writeArrayLength(-1, out);
        }
      } catch (StandardException ex) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "unexpected exception in writing parameters", ex);
      }
    }
  }

  public void appendFields(final StringBuilder sb) {
    sb.append("sql: " + sql);
    sb.append(" ;schema: " + schema);
    sb.append(" ;isUpdateOrDelete=").append(this.isUpdateOrDelete());
    sb.append(" ;isPreparedStatement=").append(this.isPreparedStatement());
    sb.append(" ;isPreparedPhase=").append(this.isPreparedPhase());
    sb.append(" ;pvs=").append(this.pvs);
    sb.append(" ;pvsData=").append(Arrays.toString(this.pvsData));
  }

  private void readStatementPVS(final ByteArrayDataInput in)
      throws IOException, SQLException, ClassNotFoundException,
      StandardException {
    // TODO See initialize_pvs()
    int numberOfParameters = this.pvsTypes[0];
    DataTypeDescriptor[] types = new DataTypeDescriptor[numberOfParameters];
    for(int i = 0; i < numberOfParameters; i++) {
      int index = i * 3 + 1;
      SnappyResultHolder.getNewNullDVD(this.pvsTypes[index], i, types,
          this.pvsTypes[index + 1], this.pvsTypes[index + 2], true);
    }
    this.pvs = new GenericParameterValueSet(null, numberOfParameters, false/*return parameter*/);
    this.pvs.initialize(types);

    final int paramCount = this.pvs.getParameterCount();
    final int numEightColGroups = BitSetSet.udiv8(paramCount);
    final int numPartialCols = BitSetSet.umod8(paramCount);
    DVDIOUtil.readParameterValueSet(this.pvs, in, numEightColGroups,
        numPartialCols);
  }

  public ParameterValueSet getParams() throws Exception {
    if (this.pvsData != null) {
      ByteArrayDataInput dis = new ByteArrayDataInput();
      dis.initialize(this.pvsData, null);
      readStatementPVS(dis);
    }

    return this.pvs;
  }

  @Override
  public void reset() {
    super.reset();
    this.pvsData = null;
    this.pvsTypes = null;
  }
}
