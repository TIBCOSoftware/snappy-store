package com.pivotal.gemfirexd.internal.engine.distributed.execution;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.DVDIOUtil;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.SnappyResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.message.BitSetSet;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;
import com.pivotal.gemfirexd.internal.snappy.SparkSQLExecute;

public class SQLLeadNodeExecutionObject  extends LeadNodeExecutionObject {
  private String sql;
  private String schema;
  // transient members set during deserialization and used in execute
  private transient byte[] pvsData;
  private transient int[] pvsTypes;
  protected ParameterValueSet pvs;
  private transient byte leadNodeFlags;
  // possible values for leadNodeFlags
  private static final byte IS_PREPARED_STATEMENT = 0x1;
  private static final byte IS_PREPARED_PHASE = 0x2;
  private static final byte IS_UPDATE_OR_DELETE_OR_PUT = 0x4;

  public SQLLeadNodeExecutionObject(String sql, String schema,
    ParameterValueSet inpvs, boolean isPreparedStatement,
    boolean isPreparedPhase, Boolean isUpdateOrDeleteOrPut) {
    this.schema = schema;
    this.sql = sql;
    this.pvs = inpvs;
    if (isPreparedStatement) leadNodeFlags |= IS_PREPARED_STATEMENT;
    if (isPreparedPhase) leadNodeFlags |= IS_PREPARED_PHASE;
    if (isUpdateOrDeleteOrPut) leadNodeFlags |= IS_UPDATE_OR_DELETE_OR_PUT;
  }

  /**
   * Default constructor for deserialization. Not to be invoked directly.
   */
  public SQLLeadNodeExecutionObject() {}

  public SparkSQLExecute getSparkSQlExecute(Version v,
      LeadNodeExecutionContext ctx, Object dfObject) throws Exception {
    if (isPreparedStatement() && !isPreparedPhase())  {
      getParams();
    }
    return CallbackFactoryProvider.getClusterCallbacks().getSQLExecute(dfObject,
      sql, schema, ctx, v, this.isPreparedStatement(), this.isPreparedPhase(), this.pvs);
  }

  public boolean isPreparedStatement() {
    return (leadNodeFlags & IS_PREPARED_STATEMENT) != 0;
  }

  public boolean isPreparedPhase() {
    return (leadNodeFlags & IS_PREPARED_PHASE) != 0;
  }

  public boolean isUpdateOrDeleteOrPut() { return (leadNodeFlags & IS_UPDATE_OR_DELETE_OR_PUT) != 0; }

  public ParameterValueSet getParams() throws Exception {
    if (this.pvsData != null) {
      ByteArrayDataInput dis = new ByteArrayDataInput();
      dis.initialize(this.pvsData, null);
      readStatementPVS(dis);
    }

    return this.pvs;
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

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.sql = DataSerializer.readString(in);
    this.schema = DataSerializer.readString(in);

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
    DataSerializer.writeString(this.sql, out);
    DataSerializer.writeString(this.schema , out);

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

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(this.getClass().getSimpleName());
    sb.append(":");
    this.appendFields(sb);
    return sb.toString();
  }

  public void appendFields(final StringBuilder sb) {
    sb.append("sql: " + sql);
    sb.append(" ;schema: " + schema);
    sb.append(" ;isUpdateOrDelete=").append(this.isUpdateOrDeleteOrPut());
    sb.append(" ;isPreparedStatement=").append(this.isPreparedStatement());
    sb.append(" ;isPreparedPhase=").append(this.isPreparedPhase());
    sb.append(" ;pvs=").append(this.pvs);
    sb.append(" ;pvsData=").append(Arrays.toString(this.pvsData));
  }

  @Override
  public void reset() {
    this.pvsData = null;
    this.pvsTypes = null;
  }

  @Override
  public byte getGfxdID() {
    return SQL_LEAD_NODE_EXEC_OBJECT;
  }

  @Override
  public String getExceptionString() {
    return this.sql;
  }

  @Override
  public String getSql() {
    return this.sql;
  }
}
