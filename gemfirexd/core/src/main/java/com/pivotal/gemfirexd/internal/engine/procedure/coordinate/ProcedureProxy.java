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

package com.pivotal.gemfirexd.internal.engine.procedure.coordinate;

import java.io.Serializable;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.execute.AbstractExecution;
import com.gemstone.gemfire.internal.cache.execute.FunctionStats;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalExecRowLocation;
import com.pivotal.gemfirexd.internal.engine.ddl.ServerGroupsTableAttribute;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdListPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.engine.procedure.
    DistributedProcedureCallFunction;
import com.pivotal.gemfirexd.internal.engine.procedure.
    DistributedProcedureCallFunction.DistributedProcedureCallFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.sql.execute.FunctionUtils;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ResultSetFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameter;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.IndexRow;
import com.pivotal.gemfirexd.procedure.ProcedureResultProcessor;

/**
 * This class is used as a procedure proxy in the coordinate node. 
 * @author yjing
 *
 */
public final class ProcedureProxy implements
    AbstractExecution.ExecutionNodesListener {

  private final Activation activation;

  private final GeneratedMethod startKeyGetter;

  private final int startSearchOperator;

  private final GeneratedMethod stopKeyGetter;

  private int stopSearchOperator;

  private final boolean sameStartStopPosition;

  //private final boolean all;

  private Set<String> groups;

  private final DataValueDescriptor[] probeValues;

  private UUID tableId;

  private final long indexId;
  
  private final int numColumns;

  // private LanguageConnectionContext lcc;
  private int resultSetNumber;

 

 // private Object[] inOutParameters;

 // private Object[] outParameters;

  private final java.sql.ResultSet[][] outResultSets;

  //private ProcedureProcessorContextImpl ppc;

  private final ProcedureResultProcessor prp;

  private ProcedureResultCollector prc;

  private final DistributedProcedureCallNode dnode;
  
  private final boolean nowait;
  
  private final boolean all;

  /*original code
  private static Pattern pattern = Pattern
      .compile("\\s*(?i)(table)\\s+(\\S+)\\s+(?i)(where)\\s+(\\S+.*)");
  */
  private static Pattern pattern = Pattern
      .compile("\\s*(?i)(table)\\s+(\\S+)(\\s+(?i)(where)\\s+(\\S+.*))?");

  /**
   * @param activation
   * @param probeValues         if the where clause contains a IN operator
   * @param startKeyGetter
   * @param startSearchOperator
   * @param stopKeyGetter
   * @param stopSearchOperator
   * @param tableIdIndex        the base table index in activation
   * @param all                 execute the procedure in all nodes
   * @param serverGroupIdx      execute the procedure in a set of groups.
   */
  public ProcedureProxy(Activation activation, int tableIdIndex, long indexId, int numColumns,
      GeneratedMethod startKeyGetter, int startSearchOperator,
      GeneratedMethod stopKeyGetter, int stopSearchOperator,
      boolean sameStartStopPosition, DataValueDescriptor[] probeValues,
      int ordering, boolean all, int serverGroupIdx, int distributedProcNodeIdx,
      boolean nowait,
      ProcedureResultProcessor prp, /*DataValueDescriptor[] parameterSet,*/
      java.sql.ResultSet[][] dynamicResultSets) {
    this.activation = activation;
    if (tableIdIndex == -1) {
      this.tableId = null;
    }
    else {
      this.tableId = (UUID)this.activation.
          getSavedObject(tableIdIndex);
    }
    this.indexId = indexId;
    this.numColumns=numColumns;
    this.probeValues = probeValues;
    this.startKeyGetter = startKeyGetter;
    this.startSearchOperator = startSearchOperator;
    this.stopKeyGetter = stopKeyGetter;
    this.stopSearchOperator = stopSearchOperator;
    this.sameStartStopPosition = sameStartStopPosition;
    //@yjing need to modification 
    //this.all = all;
    this.nowait = nowait;
    ServerGroupsTableAttribute sgtattr = (ServerGroupsTableAttribute)activation
        .getSavedObject(serverGroupIdx);
    if (sgtattr != null) {
      this.groups = sgtattr.getServerGroupSet();
    }
    this.dnode = (DistributedProcedureCallNode)activation
    .getSavedObject(distributedProcNodeIdx);
    this.prp = prp;
  //  this.inParameters =null;
    this.outResultSets = dynamicResultSets;
    this.all = all;
  }

  public void execute() throws Exception {
    //first initialize the ProcedureResultProcessor;
    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();
    Region<?, ?> region = null;

    String sqlText=this.activation.getPreparedStatement().getSource();
    String[] temp=sqlText.split("(?i)( WITH )|( ON )");
    String  sqlTextCohort=temp[0];
    if (sqlTextCohort != null && sqlTextCohort.charAt(0) == '{') {
      sqlTextCohort = sqlTextCohort.substring(1);
    }
    String whereClause=temp[1];
    String tableName = null;

    if (temp != null) {
      for (int i = 0; i < temp.length; i++) {
        Matcher m = pattern.matcher(temp[i]);
        if (m.find()) {
          tableName = m.group(2);
          whereClause = m.group(5);
          /*original code
          whereClause = m.group(4);
          */
          break;
        }
      }
    }

    int outRSNum =this.outResultSets==null? 0: outResultSets.length;
    this.prc = new ProcedureResultCollector(outRSNum, sqlTextCohort);
    final String defaultSchema = lcc.getDefaultSchema().getSchemaName();
    final long connId = lcc.getConnectionId();
    final long timeOutMillis = this.activation.getTimeOutMillis();
    final long stmtId = this.activation.getStatementID();
    Properties props = new Properties();
    props.setProperty(Attribute.QUERY_HDFS, Boolean.toString(lcc.getQueryHDFS()));
    props.setProperty(Attribute.NCJ_BATCH_SIZE,
        Integer.toString(lcc.getNcjBatchSize()));
    props.setProperty(Attribute.NCJ_CACHE_SIZE,
        Integer.toString(lcc.getNcjCacheSize()));

    ProcedureProcessorContextImpl ppc = new ProcedureProcessorContextImpl(lcc,
        this.prc, region, whereClause, this.dnode, tableName);

    this.prp.init(ppc);

    //set the out put resultSet.

    ResultSetFactory rsf = this.activation.getExecutionFactory()
        .getResultSetFactory();
    BaseActivation ba;
    if (this.activation instanceof BaseActivation) {
      ba = (BaseActivation)this.activation;
    }
    else {
      throw new AssertionError(
          "The superclass of this activation is not BaseActivation!");
    }
    //@todo yjing investigate the code, see if the following implementation is necessary. If the
    //the result description is null in the activation at this time, this implementation is 
    //unnecessary.
    if (outRSNum > 0) {
      ProxyResultDescription[] prds = new ProxyResultDescription[outRSNum];
      //ScrollInsensitiveResultSet[] scrollResultSets = new ScrollInsensitiveResultSet[outRSNum];
      ResultDescription oldRD = ba.switchResultDescription(null);

      for (int i = 0; i < outRSNum; i++) {
        java.sql.ResultSet[] outResultSet = outResultSets[i];
        assert outResultSet.length == 1: "the output result set is "
            + "supposed to be ResultSet[1]";
        // get the procedure processor result set;
        NoPutResultSet rs = rsf.getProcedureProcessorResultSet(
            activation, i, prp);

        // set ResultDescription
        ProxyResultDescription prd = new ProxyResultDescription(true);
        prds[i] = prd;
        ba.switchResultDescription(prd);

        // get the scan result set to wrapper the procedure result set,
        /*
        NoPutResultSet rs = rsf.getScrollInsensitiveResultSet(resultset,
            activation, resultSetNumber, 0, true, 0.0, 0.0);
        */
        rs.markAsTopResultSet();
        rs.openCore();
        //scrollResultSets[i] = (ScrollInsensitiveResultSet)rs;

        // get the java.sql.ResultSet wrapper the result set
        outResultSet[0] = ppc.getResultSet(rs);
      }
      ba.switchResultDescription(oldRD);
      this.prc.setProxyResultDescritptions(prds);
      //this.prc.setScrollResultSets(scrollResultSets);
    }

    @SuppressWarnings("unchecked")
    Set<Object> routingObjects = new THashSet();
    //prepare the parameter value set

    DataValueDescriptor[] inParameters = null;
    int[][] parameterInfo = null;
    GenericParameterValueSet pvs = (GenericParameterValueSet)this.activation
        .getParameterValueSet();
    if (pvs != null) {
      int numParameters = pvs.getParameterCount();
      // 3 1st SQLType 2nd Scale 3rd precision

      if (numParameters > 0) {
        inParameters = new DataValueDescriptor[numParameters];
        parameterInfo = new int[numParameters][3];

        for (int i = 0; i < numParameters; ++i) {
          inParameters[i] = pvs.getParameter(i);
          GenericParameter gp = pvs.getGenericParameter(i);
          parameterInfo[i][0] = gp.getRegisterOutputType();
          parameterInfo[i][1] = gp.getScale();
          parameterInfo[i][2] = gp.getPrecision();
        }
      }
    }
    
    final boolean isPossibleDuplicate = lcc.isPossibleDuplicate();
    if (this.groups != null && this.groups.size() > 0) {
      callFunctionServiceOnServerGroups(sqlTextCohort, defaultSchema, connId,
          inParameters, parameterInfo, isPossibleDuplicate, props, timeOutMillis,
          stmtId);
    }
    else {
      if (this.tableId != null) {
        DataDictionary db = lcc.getDataDictionary();
        TableDescriptor td = db.getTableDescriptor(this.tableId);
        region = Misc.getRegion(td, lcc, false, false);

        if (region != null
            && (this.startKeyGetter != null || this.stopKeyGetter != null)) {
          generateRoutingObjects(region, routingObjects);
        }
        if (routingObjects.size() == 0) {
          routingObjects = null;
        }
        callFunctionServiceOnTable(sqlTextCohort, routingObjects, region,
            whereClause, tableName, defaultSchema, connId, inParameters,
            parameterInfo, isPossibleDuplicate, props, timeOutMillis, stmtId);
      }
      else if (!this.all && this.tableId == null && (this.groups == null || this.groups.isEmpty())) {
        callFunctionServiceOnAllOrLocal(sqlTextCohort, region, whereClause, tableName,
            defaultSchema, connId, inParameters, parameterInfo,
            isPossibleDuplicate, false, props, timeOutMillis, stmtId);
      }
      else {
        callFunctionServiceOnAllOrLocal(sqlTextCohort, region, whereClause, tableName,
            defaultSchema, connId, inParameters, parameterInfo,
            isPossibleDuplicate, true, props, timeOutMillis, stmtId);
      }
    }
    if (this.nowait) {
      // TODO: SW: looks like a basic bug to me -- a runtime
      // entity ProcedureProxy being embedded in a compile-time entity
      this.dnode.setProcedureProxy(this);
    }
    else {
      // Neeraj:
      // If no output result sets are expected then there would not be any reply
      // so need to send a token from all the data store nodes to indicate
      // that the procedure execution is over
      // [sumedh] getResult() is now invoked by call* methods themselves
      //this.prc.getResultFromTheInternalRCForBlocking();
    }
    // Neeraj: Fix for #40924. This will block the thread calling the procedure
    // But doing this just in case when no results are expected. In case of results
    // when the user will iterate over the result then it will anyways get blocked on the
    // Blocking queue which will be populated by the processor threads.
    //if (outRSNum == 0) {
      //this.prc.getResultFromTheInternalRCForBlocking();
    //}
  }

  @Override
  public void afterExecutionNodesSet(AbstractExecution execution) {
    this.prc.initializeResultSets(execution.getExecutionNodes());
  }

  @Override
  public void reset() {
    this.prc.clearResults();
  }

  private void callFunctionServiceOnServerGroups(String sqlText,
      String defaultSchema, long connId, DataValueDescriptor[] parameters,
      int[][] parameterInfo, boolean isPossibleDuplicate, Properties props,
      long timeOutMillis, long stmtId) throws StandardException {
    DistributedProcedureCallFunctionArgs args = DistributedProcedureCallFunction
        .newDistributedProcedureCallFunctionArgs(sqlText, null, null, defaultSchema,
            connId, parameters, parameterInfo, props, timeOutMillis, stmtId);
    final FunctionUtils.GfxdExecution exec = ServerGroupUtils.onServerGroups(
        this.groups, true);
    exec.setRequireExecutionNodes(this);
    InternalDistributedSystem iDS = Misc.getDistributedSystem();
    FunctionStats stats = FunctionStats.getFunctionStats(
        DistributedProcedureCallFunction.FUNCTIONID, iDS);
    try {
      stats.incFunctionExecutionsRunning();
      final ResultCollector<?, ?> rc = FunctionUtils.executeFunction(exec,
          args, DistributedProcedureCallFunction.FUNCTIONID, sqlText, this.prc,
          this.nowait, isPossibleDuplicate, true, null, null);
      // Neeraj: This is because of bug #40963. This proxy object was getting
      // GCed and the processor was disappearing from the ProcessorKeeper21's
      // map as there it is stored as a weak reference. So keeping one reference
      // in PRC object
      this.prc.setRC(rc);
      stats.decFunctionExecutionsRunning();
      stats.incFunctionExecutionsCompleted();
    } catch (GemFireException gfeex) {
      stats.decFunctionExecutionsRunning();
      throw Misc.processGemFireException(gfeex, gfeex, "execution of "
          + sqlText, true);
    }
  }

  private void callFunctionServiceOnTable(String sqlText,
      Set<Object> routingObjects, Region<?, ?> region, String whereClause,
      String tableName, String defaultSchema, long connId,
      DataValueDescriptor[] parameters, int[][] parameterInfo,
      boolean isPossibleDuplicate, Properties props, long timeOutMillis, 
      long stmtId)
      throws StandardException {

    DistributedProcedureCallFunctionArgs args = DistributedProcedureCallFunction
        .newDistributedProcedureCallFunctionArgs(sqlText, whereClause, tableName,
            defaultSchema, connId, parameters, parameterInfo, props, 
            timeOutMillis, stmtId);
    final FunctionUtils.GfxdExecution execution = FunctionUtils
        .onRegion(region);
    execution.setRequireExecutionNodes(this);
    InternalDistributedSystem iDS = Misc.getDistributedSystem();
    FunctionStats stats = FunctionStats.getFunctionStats(
        DistributedProcedureCallFunction.FUNCTIONID, iDS);    
    try {
//      if (routingObjects.contains(QueryInfoConstants.TOK_ALL_NODES)) {
//        routingObjects = null;
//      }
      stats.incFunctionExecutionsRunning();
      final ResultCollector<?, ?> rc = FunctionUtils.executeFunction(execution,
          args, DistributedProcedureCallFunction.FUNCTIONID, sqlText, this.prc,
          this.nowait, isPossibleDuplicate, true, null, routingObjects);
      // Neeraj: This is because of bug #40963. This proxy object was getting
      // GCed and the processor was disappearing from the ProcessorKeeper21's
      // map as there it is stored as a weak reference. So keeping one reference
      // in PRC object
      this.prc.setRC(rc);
      stats.decFunctionExecutionsRunning();
      stats.incFunctionExecutionsCompleted();
    } catch (GemFireException gfeex) {
      stats.decFunctionExecutionsRunning();
      throw Misc.processGemFireException(gfeex, gfeex, "execution of "
          + sqlText, true);
    }
  }

  private void callFunctionServiceOnAllOrLocal(String sqlText, Region<?, ?> region,
      String whereClause, String tableName, String defaultSchema, long connId, 
      DataValueDescriptor[] parameters, int[][] parameterInfo,
      boolean isPossibleDuplicate, boolean onAll, Properties props, 
      long timeOutMillis, long stmtId) throws StandardException {
    DistributedProcedureCallFunctionArgs args = DistributedProcedureCallFunction
        .newDistributedProcedureCallFunctionArgs(sqlText, whereClause, tableName,
            defaultSchema, connId, parameters, parameterInfo, props, 
            timeOutMillis, stmtId);
    final FunctionUtils.GfxdExecution execution;
    if (region == null) {
      execution = new FunctionUtils.GetMembersFunctionExecutor(
          Misc.getDistributedSystem(), null, true, !onAll);
    }
    else {
      execution = FunctionUtils.onRegion(region);
    }
    execution.setRequireExecutionNodes(this);
    InternalDistributedSystem iDS = Misc.getDistributedSystem();
    FunctionStats stats = FunctionStats.getFunctionStats(
        DistributedProcedureCallFunction.FUNCTIONID, iDS);   

    try {
      stats.incFunctionExecutionsRunning();
      final ResultCollector<?, ?> rc = FunctionUtils.executeFunction(execution,
          args, DistributedProcedureCallFunction.FUNCTIONID, sqlText, this.prc,
          this.nowait, isPossibleDuplicate, true, null, null);
      // Neeraj: This is because of bug #40963. This proxy object was getting
      // GCed and the processor was disappearing from the ProcessorKeeper21's
      // map as there it is stored as a weak reference. So keeping one reference
      // in PRC object
      this.prc.setRC(rc);
      stats.decFunctionExecutionsRunning();
      stats.incFunctionExecutionsCompleted();      
    } catch (GemFireException gfeex) {
      stats.decFunctionExecutionsRunning();
      throw Misc.processGemFireException(gfeex, gfeex, "execution of "
          + sqlText, true);
    }
  }

  protected void generateRoutingObjects(Region<?, ?> region,
      Set<Object> routingObjects) throws StandardException {

    GfxdPartitionResolver resolver = null;
    //get partition resolver if it exists
    if (region instanceof PartitionedRegion) {
      resolver = (GfxdPartitionResolver)((PartitionedRegion)region)
          .getPartitionResolver();
    }
    else {
      //replicate region, return    
      return;
    }

    assert resolver != null: "The resolver is not supposed to be null!";
    DataValueDescriptor[] startKey = null;
    DataValueDescriptor[] stopKey = null;
    Object retValue = null; //the startKeyGetter

    //get start key value
    retValue = startKeyGetter.invoke(this.activation);
    assert (retValue instanceof IndexRow): " the key is supposed to IndexRow!";
    startKey = ((IndexRow)retValue).getRowArray();

    //get stop key value
    if (this.sameStartStopPosition) {
      stopKey = startKey;
      this.stopSearchOperator=this.startSearchOperator;
    }
    else {
      retValue = stopKeyGetter.invoke(this.activation);
      assert (retValue instanceof IndexRow): " the key is supposed to IndexRow!";
      stopKey = ((IndexRow)retValue).getRowArray();
    }
    if (this.indexId == -1) { // based on the partition columns
      searchRoutingObjectsOnPartitionColumns(this.probeValues, startKey,
          this.startSearchOperator, stopKey, this.stopSearchOperator,
          this.sameStartStopPosition, resolver, routingObjects);
    }
    else { // based on the global hash index
      searchRoutingObjectOnGlobalIndex(this.probeValues, startKey,
          this.startSearchOperator, stopKey, this.stopSearchOperator,
          this.sameStartStopPosition, routingObjects);
    }
  }

  protected void searchRoutingObjectsOnPartitionColumns(DataValueDescriptor[] probeValues, 
                         DataValueDescriptor[] startKey, int startSearchOperator, 
                         DataValueDescriptor[] stopKey,  int stopSearchOperator, 
                         boolean sameStartStopPosition,   GfxdPartitionResolver resolver,
                         Set<Object> routingObjects) {
  //no IN in the where clause
    if (probeValues == null) {
      generateRoutingObjectOnKeys(startKey, startSearchOperator, stopKey,
           stopSearchOperator, sameStartStopPosition, resolver, routingObjects);
    }
    else { //IN in the where clause
      //in this case, we suppose that the start key and stop key are equal. In addition
      //the column for the IN operator is the first element of the start key array. 
      //@TODO yjing. Check if the derby supports the index position in 
      //the start key  is greater than 0????
      for (int probeIndex = 0; probeIndex < probeValues.length; probeIndex++) {
        startKey[0] = probeValues[probeIndex];
        stopKey[0] = probeValues[probeIndex];
        generateRoutingObjectOnKeys(startKey, startSearchOperator,
            stopKey, stopSearchOperator, sameStartStopPosition, resolver, routingObjects);
      }
    }
         
    
    
    
  }
  /***
   * This method gets routing objects for a search range. If the startKey and stopKey is
   * equal and their operators are compatible, the partition resolver will return one 
   * routing object. If the startKey and stopKey implies a range of the partition value, 
   * then only the range partition resolver will returns one or several routing objects; 
   * other partition strategies (hash and list) returns null. 
   */

  protected void generateRoutingObjectOnKeys(DataValueDescriptor[] startKey,
      int startSearchOperator, DataValueDescriptor[] stopKey,
      int stopSearchOperator, boolean sameStartStopPosition,
      GfxdPartitionResolver resolver, Set<Object> routingObjects) {

    if (resolver instanceof GfxdRangePartitionResolver) {
      boolean lowerBoundInclusive = startSearchOperator == ScanController.GE;
      boolean upperBoundInclusive = stopSearchOperator != ScanController.GE;
      assert (startKey.length > 0 && stopKey.length > 0):
        "Each search key at least contains one element!";

      Object[] ros = resolver.getRoutingObjectsForRange(startKey[0],
          lowerBoundInclusive, stopKey[0], upperBoundInclusive);
      if (ros != null) {
        for (Object o : ros) {
          routingObjects.add(o);
        }
      }

    }
    else {
      if (resolver instanceof GfxdListPartitionResolver) {
        //make sure only discrete values are considered.
        if(!sameStartStopPosition) {
           return;
        }
        
        assert (startKey.length>0) :"Each search key at least contains one element!";
        Object[] ros = resolver.getRoutingObjectsForRange(startKey[0],
            true, stopKey[0], true);
        if (ros != null) {
          for (Object o : ros) {
            routingObjects.add(o);
          }
        }

      }
      else {

        if (sameStartStopPosition) {
          Object value = resolver
              .getRoutingObjectsForPartitioningColumns(startKey);
          routingObjects.add(value);
        }
      }

    }

  }

  protected void searchRoutingObjectOnGlobalIndex(
      DataValueDescriptor[] probeValue, DataValueDescriptor[] startKey,
      int startSearchOperator, DataValueDescriptor[] stopKey,
      int stopSearchOperator, boolean sameStartStopPosition, Set<Object> routingObjects)
      throws StandardException {

    GlobalExecRowLocation rowLocation = new GlobalExecRowLocation();
    DataValueDescriptor[] row = new DataValueDescriptor[this.numColumns];
    row[this.numColumns - 1] = rowLocation;
    FormatableBitSet scanColumnList = new FormatableBitSet(this.numColumns);
    scanColumnList.set(this.numColumns-1);
    TransactionController tc = this.activation.getTransactionController();
    ScanController sc = null;
    if (probeValues == null) {
      sc = tc.openScan(this.indexId, false, 0 /* read only */,
          TransactionController.MODE_RECORD,
          TransactionController.ISOLATION_READ_COMMITTED, scanColumnList, startKey,
          startSearchOperator, null, stopKey, stopSearchOperator, null);

      if (sc.next()) {
        sc.fetch(row);
               
        Serializable routingObject =rowLocation.getRoutingObject();
        routingObjects.add(routingObject);
      }
    }
    else {
      for (int probeIndex = 0; probeIndex < probeValue.length; probeIndex++) {
        startKey[0] = probeValue[probeIndex];
        stopKey[0] = probeValue[probeIndex];
        if (sc == null) {
          sc = tc.openScan(this.indexId, false, 0 /* read only */,
              TransactionController.MODE_RECORD,
              TransactionController.ISOLATION_READ_COMMITTED, scanColumnList, startKey,
              startSearchOperator, null, stopKey, stopSearchOperator, null);
        }
        else {
          sc.reopenScan(startKey, startSearchOperator, null, stopKey,
              stopSearchOperator, null);
        }

        if (sc.next()) {
          sc.fetch(row);          
          Serializable routingObject =rowLocation.getRoutingObject();
          routingObjects.add(routingObject);
        }

      }
    }
  }

  public void setProxyParameterValueSet() {
    GenericParameterValueSet gpvs = (GenericParameterValueSet)this.activation
        .getParameterValueSet();
    ProxyParameterValueSet proxyPVS = new ProxyParameterValueSet(
        this.activation, gpvs, this.prp);
    ((BaseActivation)this.activation).setProxyParameterValueSet(proxyPVS);
  }
}
