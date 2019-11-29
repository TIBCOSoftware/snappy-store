package com.pivotal.gemfirexd.internal.engine.distributed.execution;

import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;
import com.pivotal.gemfirexd.internal.snappy.SparkSQLExecute;

public abstract class LeadNodeExecutionObject implements GfxdSerializable {
  public abstract SparkSQLExecute getSparkSQlExecute(Version v,
    LeadNodeExecutionContext ctx, Object dfObject) throws Exception;
  public abstract boolean isUpdateOrDeleteOrPut();
  public abstract void reset();

  @Override
  public final int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }
  public Version[] getSerializationVersions() {
    return null;
  }
  public abstract String getExceptionString();
  public String getSql() { return null; }
}
