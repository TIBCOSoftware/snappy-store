package com.pivotal.gemfirexd.internal.engine.distributed.execution;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.distributed.DVDIOUtil;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;
import com.pivotal.gemfirexd.internal.snappy.SparkSQLExecute;

public class SampleInsertExecutionObject extends LeadNodeExecutionObject {

  private String baseTableName;
  private List<DataValueDescriptor[]> rows = null;
  private byte[] serializedDVDs = null;
  public SampleInsertExecutionObject(String baseTableName, List<DataValueDescriptor[]>
    rows) {
    this.baseTableName = baseTableName;
    this.rows = rows;
  }

  /**
   * Default constructor for deserialization. Not to be invoked directly.
   */
  public SampleInsertExecutionObject() {}

  public SparkSQLExecute getSparkSQlExecute(Version v, LeadNodeExecutionContext ctx) throws Exception {

    return CallbackFactoryProvider.getClusterCallbacks().getSampleInsertExecute( this.baseTableName,
      ctx, v, this.rows, this.serializedDVDs);
  }

  @Override
  public boolean isUpdateOrDeleteOrPut() {
    return true;
  }

  @Override
  public void reset() {

  }

  @Override
  public byte getGfxdID() {
    return SAMPLE_INSERT_EXEC_OBJECT;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeString(this.baseTableName, out);
    int numCols = this.rows.get(0).length;
    int numEightColGroups = numCols / 8 + (numCols % 8 == 0 ? 0 : 1);
    byte numPartCols = (byte)(numCols % 8);
    if (numPartCols == 0) {
      numPartCols = 8;
    }
    int[] dvdTypes = new int[numCols * 3];
    DataValueDescriptor[] row = rows.get(0);
    for(int i = 0 ; i < numCols; ++i) {
      DataValueDescriptor dvd = row[i];
      dvdTypes[i * 3] = dvd.getTypeFormatId();
      if (dvd instanceof SQLDecimal) {
        dvdTypes[i * 3 + 1] = ((SQLDecimal)dvd).getDecimalValuePrecision();
        dvdTypes[i * 3 + 2] = ((SQLDecimal)dvd).getDecimalValueScale();
      } else {
        dvdTypes[i * 3 + 1] = -1;
        dvdTypes[i * 3 + 2] = -1;
      }
    }

    final HeapDataOutputStream hdos = new HeapDataOutputStream();

    DataSerializer.writePrimitiveInt(numEightColGroups, hdos);
    DataSerializer.writePrimitiveByte(numPartCols, hdos);
    DataSerializer.writeIntArray(dvdTypes, hdos);
    DataSerializer.writePrimitiveInt(rows.size(), hdos);
    try {
      for (DataValueDescriptor[] dvds : rows) {
        DVDIOUtil.writeDVDArray(dvds, numEightColGroups, numPartCols, hdos);
      }
    } catch(StandardException se) {
      throw new IOException(se);
    }
    InternalDataSerializer.writeArrayLength(hdos.size(), out);
    hdos.sendTo(out);

  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.baseTableName = DataSerializer.readString(in);
    this.serializedDVDs = DataSerializer.readByteArray(in);
  }

  @Override
  public String getExceptionString() {
    return "";
  }
}
