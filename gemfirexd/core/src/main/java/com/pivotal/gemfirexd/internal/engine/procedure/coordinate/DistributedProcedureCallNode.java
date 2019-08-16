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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Vector;

import com.gemstone.gemfire.cache.Region;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.ServerGroupsTableAttribute;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ClassName;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.classfile.VMOpcode;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.LocalField;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.MethodBuilder;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.StatementType;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Optimizable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Optimizer;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.RowOrdering;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDescriptorGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.util.JBitSet;
import com.pivotal.gemfirexd.internal.impl.sql.compile.*;

/**
 * This represents a procedure call in the coordinate node.
 * 
 * @author yjing
 * 
 */

/**
 * @author yjing
 *
 */
public class DistributedProcedureCallNode extends StaticMethodCallNode
    implements Serializable {

  private static final long serialVersionUID = -5431461816663110294L;

  private transient FromList onTable;

  private ValueNode whereClause;

  private boolean all;

  private ServerGroupsTableAttribute serverGroups;

  //private Boolean originalWhereClauseHadSubqueries = false;

  private transient ProcedureProxy procProxy;
  //@TODO yjing the following two fields can be removed after carefully arranging the oder of the
  //code generation.
 // private LocalField dynamicParamField;

  private LocalField procedureResultSetsHolder;

  //this predicate list is used to find the routing objects
  transient PredicateList restrictionPredicates;

  private UUID tableId = null;

  private long indexId = -1;
  private int numColumns=0;

  transient FromBaseTable table = null;

  private transient ProcedureProcessorNode ppn = null;

  private boolean nowait;
 // private int outParametersNum=0;
  
  public DistributedProcedureCallNode() {

  }

  @Override
  public void init(Object arg1, Object arg2, Object arg3, Object arg4,
      Object arg5, Object arg6, Object arg7) throws StandardException {

    StaticMethodCallNode callNode = (StaticMethodCallNode)arg1;
    super.init(callNode.procedureName, callNode.javaClassName);
    this.methodParms = callNode.methodParms;
    this.ppn = (ProcedureProcessorNode)arg2;
    this.onTable = (FromList)arg3;
    this.whereClause = (ValueNode)arg4;
    this.all = (Boolean)arg5;
    this.serverGroups = (ServerGroupsTableAttribute)arg6;
    this.nowait = (Boolean)arg7;
    /*
    if (this.whereClause != null) {
      CollectNodesVisitor cnv = new CollectNodesVisitor(SubqueryNode.class,
          SubqueryNode.class);
      this.whereClause.accept(cnv);
      if (!cnv.getList().isEmpty()) {
        this.originalWhereClauseHadSubqueries = true;
      }
    }
    */
  }

  @Override
  public JavaValueNode bindExpression(FromList fromList,
      SubqueryList subqueryList, Vector aggregateVector)
      throws StandardException {
    DataDictionary dd = getDataDictionary();
    this.ppn.bindExpression(dd);
    if (this.onTable.size() == 1/* && this.whereClause != null*/) {

      bindExpressions(dd);
    }
    JavaValueNode node = super.bindExpression(fromList, subqueryList, aggregateVector);
    this.lcc = null;
    return node;
  }

  /**
   * Do code generation for this method call
   * 
   * @param acb
   *                The ExpressionClassBuilder for the class we're generating
   * @param mb
   *                The method the expression will go into
   * 
   * 
   * @exception StandardException
   *                    Thrown on error
   */
  @Override
  public void generateExpression(ExpressionClassBuilder acb, MethodBuilder mb)
      throws StandardException {

    generateParameters(acb, mb);

   // LocalField functionEntrySQLAllowed = null;

    if (routineInfo != null) {

      short sqlAllowed = routineInfo.getSQLAllowed();

      // Before we set up our authorization level, add a check to see if this
      // method can be called. If the routine is NO SQL or CONTAINS SQL
      // then there is no need for a check. As follows:
      //
      // Current Level = NO_SQL - CALL will be rejected when getting CALL result
      // set
      // Current Level = anything else - calls to procedures defined as NO_SQL
      // and CONTAINS SQL both allowed.

      if (sqlAllowed != RoutineAliasInfo.NO_SQL) {

        int sqlOperation;

        if (sqlAllowed == RoutineAliasInfo.READS_SQL_DATA)
          sqlOperation = Authorizer.SQL_SELECT_OP;
        else if (sqlAllowed == RoutineAliasInfo.MODIFIES_SQL_DATA)
          sqlOperation = Authorizer.SQL_WRITE_OP;
        else
          sqlOperation = Authorizer.SQL_ARBITARY_OP;

        // GemStone changes BEGIN
        /*(original code) generateAuthorizeCheck((ActivationClassBuilder)acb, mb, sqlOperation); */
        generateAuthorizeCheck(acb, mb, sqlOperation);
        // GemStone changes END
      }

      // ?? yjing this node only for procedure call; no concern on function.
      // /// int statmentContextReferences = isSystemCode ? 2 : 1;

      // /// boolean isFunction = routineInfo.getReturnType() != null;

      // //// if (isFunction)
      // //// statmentContextReferences++;

      // ///// if (statmentContextReferences != 0) {
      acb.pushThisAsActivation(mb);
      mb.callMethod(VMOpcode.INVOKEINTERFACE, null,
          "getLanguageConnectionContext", ClassName.LanguageConnectionContext,
          0);
      mb.callMethod(VMOpcode.INVOKEINTERFACE, null, "getStatementContext",
          "com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext", 0);

      // // for (int scc = 1; scc < statmentContextReferences; scc++)
      // // mb.dup();
      // /// }

      // ?? yjing why does the statement context got duplicated???
      /**
       * Set the statement context to reflect we are running System procedures,
       * so that we can execute non-standard SQL.
       */
      // //// if (isSystemCode) {
      // /// mb.callMethod(VMOpcode.INVOKEINTERFACE, null, "setSystemCode",
      // "void",
      // /// 0);
      // /// }
      // for a function we need to fetch the current SQL control
      // so that we can reset it once the function is complete.
      // 
      // // if (isFunction) {
      // // functionEntrySQLAllowed = acb.newFieldDeclaration(Modifier.PRIVATE,
      // // "short");
      // // mb.callMethod(VMOpcode.INVOKEINTERFACE, null, "getSQLAllowed",
      // "short",
      // // 0);
      // // mb.setField(functionEntrySQLAllowed);
      // // }
      // Set up the statement context to reflect the
      // restricted SQL execution allowed by this routine.
      mb.push(sqlAllowed);
      mb.push(false);
      mb.callMethod(VMOpcode.INVOKEINTERFACE, null, "setSQLAllowed", "void", 2);

    }

    // generate the dynamic result sets.
    // add in the ResultSet arrays.
    if (routineInfo != null) {
      // int compiledResultSets = methodParameterTypes.length - methodParms.length;

      // if (compiledResultSets != 0) {

        // Add a method that indicates the maxium number of dynamic result sets.
        int maxDynamicResults = routineInfo.getMaxDynamicResultSets();
        if (maxDynamicResults > 0) {
          MethodBuilder gdr = acb.getClassBuilder().newMethodBuilder(
              Modifier.PUBLIC, "int", "getMaxDynamicResults");
          gdr.push(maxDynamicResults);
          gdr.methodReturn();
          gdr.complete();
        //}

        // add a method to return all the dynamic result sets (unordered)
        MethodBuilder gdr1 = acb.getClassBuilder().newMethodBuilder(
            Modifier.PUBLIC, "java.sql.ResultSet[][]", "getDynamicResults");

        MethodBuilder cons = acb.getConstructor();
        // if (procDef.getParameterStyle() == RoutineAliasInfo.PS_JAVA)
       // {
          // PARAMETER STYLE JAVA

          this.procedureResultSetsHolder = acb.newFieldDeclaration(
              Modifier.PRIVATE, "java.sql.ResultSet[][]");

          // create the holder of all the ResultSet arrays, new
          // java.sql.ResultSet[][compiledResultSets]
          cons.pushNewArray("java.sql.ResultSet[]", maxDynamicResults);
          cons.setField(procedureResultSetsHolder);

          // arguments for the dynamic result sets
          for (int i = 0; i < maxDynamicResults; i++) {

            mb.pushNewArray("java.sql.ResultSet", 1);
            // yjing preparing the ResultSet[] parameter for the procedure call.
            //  mb.dup();

            mb.getField(procedureResultSetsHolder);
            mb.swap();

            mb.setArrayElement(i);
          }
        //}

        // complete the method that returns the ResultSet[][] to the
        gdr1.getField(procedureResultSetsHolder);
        gdr1.methodReturn();
        gdr1.complete();

       // nargs += compiledResultSets;
      }

    }

    acb.pushGetExecutionFactoryExpression(mb);
    acb.pushThisAsActivation(mb);
    if(this.tableId!=null) {
       mb.push(acb.addItem(this.tableId));
    }
    else {
      mb.push(-1);
    }
    mb.push(this.indexId);
    mb.push(this.numColumns);

    int predicateSize =this.restrictionPredicates==null? 0:this.restrictionPredicates.size();
    if (predicateSize > 0) {

      boolean multiProbing = false;
      // determine if the predicate list contains IN
      for (int i = 0; i < predicateSize; i++) {
        Predicate pred = (Predicate)restrictionPredicates.elementAt(i);
        if (pred.isInListProbePredicate() && pred.isStartKey()) {

          multiProbing = true;
          break;
        }
      }

      this.restrictionPredicates.generateStartKey(acb, mb, this.table);
      mb.push(this.restrictionPredicates.startOperator(this.table));
      if(this.restrictionPredicates.sameStartStopPosition()) {
         mb.dup();
         mb.pushNull(ClassName.GeneratedMethod);
         mb.swap();
         mb.push(true);
      }
      else {
        this.restrictionPredicates.generateStopKey(acb, mb, this.table);
        mb.push(this.restrictionPredicates.stopOperator(this.table));
        mb.push(false);
      }
      if (multiProbing) {
        this.restrictionPredicates.generateInListValues(acb, mb);
      }
      else {
        mb.pushNull(ClassName.DataValueDescriptor + "[]");
        mb.push(RowOrdering.DONTCARE);
      }
    }
    else {

      mb.pushNull(ClassName.GeneratedMethod);
      mb.push(ScanController.NA);
      mb.pushNull(ClassName.GeneratedMethod);
      mb.push(ScanController.NA);
      mb.push(false);
      mb.pushNull(ClassName.DataValueDescriptor + "[]");
      mb.push(RowOrdering.DONTCARE);
    }    
    mb.push(this.all);

    //mb.pushNull("java.lang.String[]");
    mb.push(acb.addItem(this.serverGroups));
    mb.push(acb.addItem(this));
    mb.push(this.nowait);
    this.ppn.generateExpression(acb, mb);   
    if(this.procedureResultSetsHolder!=null) {
       mb.getField(this.procedureResultSetsHolder);
    }
    else {
       mb.pushNull("java.sql.ResultSet[][]");
    }

    // @TODO the argument of the ProcedureProxy constructor;
    mb.callMethod(VMOpcode.INVOKEINTERFACE, null,
        "getDistributedProcedureCallProxy", ClassName.ProcedureProxy, 17);
    if(this.outParamArrays!=null) {
       mb.dup();
    }  
    mb.callMethod(VMOpcode.INVOKEVIRTUAL, null, "execute", "void", 0);

    if (this.outParamArrays!=null) {

         MethodBuilder constructor = acb.getConstructor();

         // constructor - setting up correct paramter type info
         acb.pushThisAsActivation(constructor);
         constructor.callMethod(VMOpcode.INVOKEINTERFACE, null,
             "getParameterValueSet", ClassName.ParameterValueSet, 0);

         // execute - passing out parameters back.
        // acb.pushThisAsActivation(mb);
        // mb.callMethod(VMOpcode.INVOKEINTERFACE, null, "getParameterValueSet",
         //    ClassName.ParameterValueSet, 0);

         int[] parameterModes = routineInfo.getParameterModes();
         for (int i = 0; i < this.outParamArrays.length; i++) {

           int parameterMode = parameterModes[i];
           if (parameterMode != JDBC30Translation.PARAMETER_MODE_IN) {

             // must be a parameter if it is INOUT or OUT.
//             ValueNode sqlParamNode = ((SQLToJavaValueNode)methodParms[i])
//                 .getSQLValueNode();

             int applicationParameterNumber = applicationParameterNumbers[i];

             // Set the correct parameter nodes in the ParameterValueSet at
             // constructor time.
             constructor.dup();
             constructor.push(applicationParameterNumber);
             constructor.push(parameterMode);
             constructor.callMethod(VMOpcode.INVOKEINTERFACE, null,
                 "setParameterMode", "void", 2);

           }
         
         }
         mb.callMethod(VMOpcode.INVOKEVIRTUAL, null, "setProxyParameterValueSet", "void", 0);
         constructor.endStatement();
           
       }
       mb.endStatement();
       // [sumedh] Why?? removing to fix #46498
       /*
       //override the close() method in the BaseActivation; now it is a no operation method.
       MethodBuilder closeMethod = acb.getClassBuilder().newMethodBuilder(
           Modifier.PUBLIC, "void", "close");

       closeMethod.addThrownException(ClassName.StandardException);
       closeMethod.methodReturn();
       closeMethod.complete();    
       */
  }

  @Override
  public int getStatementType() {
    return StatementType.DISTRIBUTED_PROCEDURE_CALL;
  }
  
  public ProcedureProxy getProcedureProxy() {
    return this.procProxy;
  }
  
  public void setProcedureProxy(ProcedureProxy proxy) {
    this.procProxy = proxy;
  }

  /**
   * Generate the parameters to the given method call
   * 
   * @param acb
   *                The ExpressionClassBuilder for the class we're generating
   * @param mb
   *                the method the expression will go into
   * 
   * @return Count of arguments to the method.
   * 
   * @exception StandardException
   *                    Thrown on error
   */

  @Override
  public int generateParameters(ExpressionClassBuilder acb, MethodBuilder mb)
      throws StandardException { 
    /*
     * Generate the code for each user parameter, generating the appropriate
     * cast when the passed type needs to get widened to the expected type.
     */
    for (int param = 0; param < methodParms.length; param++) {
      // @yjing. this function push a parameter value in the stack if the
      // parameter is an IN
      // parameter. If the parameter is INOUT, a filed is declared with the
      // parameter type array
      // and the first element is set with the input value. The value (Type[])
      // is left in the
      // stack. For an out parameter, it is the same as INOUT one except no
      // value is set in the
      // first element.
      generateOneParameter(acb, mb, param);
    }
      //yjing do not need to resolve the type mismatch. who cares the types in this node.
      // type from the SQL-J expression  
     return methodParms.length;
  }

  /**
   * Push extra code to generate the casts within the arrays for the parameters
   * passed as arrays.
   */
  @Override
  public void generateOneParameter(ExpressionClassBuilder acb,
      MethodBuilder mb, int parameterNumber) throws StandardException {
    int parameterMode;

    assert routineInfo != null: "The routing info is supposed to not be null for a procedure!";
    
    parameterMode = routineInfo.getParameterModes()[parameterNumber];
    // Neeraj: commented out the below code as there is no need to push
    // procedure parameters in the stack as these parameters are never used by
    // gemfirexd DAP machinery and so these are never popped out. Everything is by
    // ProcedureProxy and on the nodes where the procedure will get actually
    // executed there derby machinery will take care of right behaviour.
    // Fix for bug #42875 -- (ASSERT FAILED items left on stack 1 occurred when call DAP in gemfirexd)
//    switch (parameterMode) {
//      case JDBC30Translation.PARAMETER_MODE_IN:
//      case JDBC30Translation.PARAMETER_MODE_IN_OUT:
//      case JDBC30Translation.PARAMETER_MODE_UNKNOWN:
//        // yjing Get the sql value of the parameter. In the most of cases, the
//        // parameter nodes are
//        // wrappered under SQLToJavaValueNode. We, however, are only interested
//        // in the SQL type of the parameters
//        // in the coordinate node. To avoid much modification on the derby code,
//        // we get rid of the
//        // SQLToJavaValueNode. In addition, if the parameter node is
//        // JavaValueNode, we need wrapper
//        // it with JavaToSQLValueNode.
//        if (sql2j != null) {
//          ValueNode sqlNode = sql2j.getSQLValueNode();       
//          sqlNode.generateExpression(acb, mb);
//        }
//        break;
//
//      case JDBC30Translation.PARAMETER_MODE_OUT:
//        // For an OUT parameter we require nothing to be pushed into the
//        // method call from the parameter node.
//        break;
//    }

    switch (parameterMode) {
      case JDBC30Translation.PARAMETER_MODE_IN:
      case JDBC30Translation.PARAMETER_MODE_UNKNOWN:
        break;

      case JDBC30Translation.PARAMETER_MODE_IN_OUT:
      case JDBC30Translation.PARAMETER_MODE_OUT: {
        // Create the array used to pass into the method. We create a
        // new array for each call as there is a small chance the
        // application could retain a reference to it and corrupt
        // future calls with the same CallableStatement object.

        String methodParameterType = methodParameterTypes[parameterNumber];
        String arrayType = methodParameterType.substring(0, methodParameterType
            .length() - 2);
        LocalField lf = acb.newFieldDeclaration(Modifier.PRIVATE,
            methodParameterType);

        // ++this.outParametersNum;
         if (outParamArrays == null)
           outParamArrays = new LocalField[methodParms.length];

         outParamArrays[parameterNumber] = lf;

         mb.pushNewArray(arrayType, 1);
         mb.setField(lf);
         break;
      }
    }
  }
  
  private void bindExpressions(DataDictionary dd) throws StandardException {
    // bind tables
    this.onTable.bindTables(dd, this.onTable);

    Vector whereAggregates = new Vector();
    SubqueryList whereSubquerys = (SubqueryList)getNodeFactory().getNode(
        C_NodeTypes.SUBQUERY_LIST, getContextManager());

    getCompilerContext().pushCurrentPrivType(Authorizer.SELECT_PRIV);
    // bind where clause
    if (whereClause != null) {
    whereClause = whereClause.bindExpression(this.onTable, whereSubquerys,
        whereAggregates);
    }

    // Do not allow the aggregate in the where clause
    if (whereClause != null && whereAggregates.size() > 0) {
      throw StandardException
          .newException(SQLState.LANG_NO_AGGREGATES_IN_WHERE_CLAUSE);
    }

    if (whereClause != null && whereSubquerys.size() > 0) {
      throw StandardException.newException(
          SQLState.LANG_NO_SUBQUERIES_IN_WHERE_CLAUSE, "CALL PROCEDURE");
    }

    /*
     * If whereClause is a parameter, (where ?/where -?/where +?), then we
     * should catch it and throw exception
     */
    if (this.whereClause != null && this.whereClause.isParameterNode())
      throw StandardException.newException(
          SQLState.LANG_NON_BOOLEAN_WHERE_CLAUSE, "PARAMETER");

    if (whereClause != null) {
      this.whereClause = this.whereClause.checkIsBoolean();
    }
    getCompilerContext().popCurrentPrivType();

    if (whereClause != null) {
      whereClause = SelectNode.normExpressions(whereClause);
      whereClause = whereClause.preprocess(1, null, null, null);
    }

    if ((this.onTable.elementAt(0) instanceof FromSubquery) ||
       (this.onTable.elementAt(0) instanceof FromVTI))
    {
    	// The table we were given is actually bound to a subquery or VTI
    	// meaning that it was really a view or virtual table 
    	// This is not meaningful for a CALL ON <table> as views do not
    	// has a 'home' and VTIs are not assigned to members
    	// (Synonyms are okay)
    	// Throw 0A000 sqlstate, or else the cast will throw unhandled exception
    	throw StandardException.newException(
    			SQLState.NOT_IMPLEMENTED, "CALL statement allowed on base table only");
    }

    this.table = (FromBaseTable)this.onTable.elementAt(0);
    this.table.initAccessPaths((Optimizer)null);

    //set base table table reference map

    JBitSet referencedTableMap = new JBitSet(1);
    referencedTableMap.set(this.table.getTableNumber());
    this.table.setReferencedTableMap(referencedTableMap);

    TableDescriptor td = this.table.getTableDescriptor();

    // If the schema is a system schema or SESSION, throw 0A000 exception
    // Easier to do this check against TableDescriptor than earlier
    if ((td.getSchemaDescriptor().isSystemSchema()) ||
       (td.getSchemaDescriptor().getSchemaName().equals(GfxdConstants.SESSION_SCHEMA_NAME)))
    {
    	throw StandardException.newException(
    			SQLState.NOT_IMPLEMENTED, "CALL statement not allowed on SYS or temporary tables");    	
    }

    this.tableId=td.getUUID();

    int numColumns = td.getColumnDescriptorList().size();
    JBitSet referencedColumns = new JBitSet(numColumns + 1);

    //get referenced columns in the where clause
    ReferencedColumnsVisitor rcv = new ReferencedColumnsVisitor(
        referencedColumns);
    if (whereClause != null) {
      this.whereClause.accept(rcv);
    }

    PredicateList wherePredicates = (PredicateList)getNodeFactory().getNode(
        C_NodeTypes.PREDICATE_LIST, getContextManager());
    wherePredicates.pullExpressions(1, whereClause);
    if (SanityManager.DEBUG) {

      if ((wherePredicates != null) && wherePredicates.size() > 0) {
        printLabel(0, "wherePredicates: ");
        wherePredicates.treePrint(1);
      }
    }

    PredicateList actionPredicates = (PredicateList)getNodeFactory().getNode(
        C_NodeTypes.PREDICATE_LIST, getContextManager());

    searchUsefulStartKeyAndStopKey(td, referencedColumns, wherePredicates,
        actionPredicates);

  }

  /**
   * This method searches the where clause with the columns as the key of  
   * GlobalHashIndex or the partition columns to determine if routing objects can be generated
   * from this where clause. 
   * A where clause could contain both the global index key columns and partition columns.
   * In this case, which kind of columns is handler first? Or the where clause is invalid and nothing
   * needs to do further. 
   * 
   *@todo yjing: solve the cases mentioned in the 
   *{@link #getUsefulPredicates(Optimizable, PredicateList, ConglomerateDescriptor) getUsefulPredicates} method  
   * 
   * 
   * @return
   */

  private void searchUsefulStartKeyAndStopKey(TableDescriptor td,
      JBitSet referencedColumns, PredicateList where,
      PredicateList actionPredicates) throws StandardException {
    assert td != null: "The table descriptor cannot be null";
    Region<?, ?> region = Misc.getRegionForTableByPath(td.getSchemaName() + "." + td.getName(), true);
    assert region != null: "The region is supposed not to be null!";

    GemFireContainer container = (GemFireContainer)region.getUserAttribute();
    if (!container.isPartitioned()) {
      //replicated region
      return;
    }
    DistributionDescriptor dd = container.getDistributionDescriptor();
    //partitioned by columns
    int[] partitionColumnPositions = null;
    if (dd.getPolicy() <= DistributionDescriptor.PARTITIONBYGENERATEDKEY
        || (partitionColumnPositions = dd.getColumnPositionsSorted()) == null) {
      return;
    }

    // set partition columns bit set
    int numColumns = td.getColumnDescriptorList().size();
    JBitSet partitionColumns = new JBitSet(numColumns + 1);
    for (int i = 0; i < partitionColumnPositions.length; ++i) {
      partitionColumns.set(partitionColumnPositions[i]);
    }

    final ArrayList<ConglomerateDescriptor> candidateConglomerates =
        new ArrayList<ConglomerateDescriptor>();

    ConglomerateDescriptorList cdl = td.getConglomerateDescriptorList();
    int numCong = cdl.size();
    boolean hasIndexForPartitionColumns=false;
    for (int index = 0; index < numCong; index++) {
      ConglomerateDescriptor cd = (ConglomerateDescriptor)cdl.get(index);
      //it is a heap
      if (!cd.isIndex()) {
        continue;
      }
      IndexRowGenerator irg = cd.getIndexDescriptor();
      String indexType = irg.indexType();

      //only consider GlobalHashIndex and Hash1Index
      if (indexType.equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE) ||
          indexType.equals(GfxdConstants.LOCAL_HASH1_INDEX_TYPE)) {
        continue;
      }

      int[] columnPositions = irg.baseColumnPositions();

      //not all index columns are in the where clause
      if (!containColumns(referencedColumns, columnPositions)) {
        continue;
      }
      if(containColumns(partitionColumns, columnPositions)) {
        hasIndexForPartitionColumns=true;
        //we want the partition columns are considered first.
        candidateConglomerates.add(0, cd);
      }
      else 
      {
         candidateConglomerates.add(cd);  
      }
    }
    //no index for partition columns, add a fake one for partition columns
    if(!hasIndexForPartitionColumns) {
       candidateConglomerates.add(0, getFakeConglomerateDescriptor(partitionColumnPositions));
    }
    
    int candidateconglomeratesNum = candidateConglomerates.size();
    for (int index = 0; index < candidateconglomeratesNum; index++) {
      
      ConglomerateDescriptor cd = candidateConglomerates.get(index);
      int[] baseColumnPositions=cd.getIndexDescriptor().baseColumnPositions();
      
      actionPredicates.removeAllElements();
      getUsefulPredicates(where, baseColumnPositions, actionPredicates);
      
      if (!usefulPredicateList(actionPredicates, baseColumnPositions.length)) {
        continue;
      }
      
      this.restrictionPredicates = actionPredicates;      
      this.indexId = cd.getConglomerateNumber();      
      this.numColumns=baseColumnPositions.length+1; //includes the RowLocation
      this.table.getTrulyTheBestAccessPath().setConglomerateDescriptor(cd);
      //we only use the first found CD now @todo deal with or
      break;
     
    }
  }
   
  /**
   * determine a set of columns covered by the where clause
   * @param referencedColumns
   * @param columnPositions
   * @return
   */
  boolean containColumns(JBitSet referencedColumns, int[] columnPositions) {
    int columnNum = columnPositions.length;
    for (int i = 0; i < columnNum; ++i) {
      if (!referencedColumns.get(columnPositions[i])) {
        return false;
      }
    }
    return true;

  }
  
  /**
   * determine the number of columns in start and stop keys are equal and
   * equal to the number of index columns.
   * @param predicates
   * @param columnNum
   * @return
   */
  boolean usefulPredicateList(PredicateList predicates, int columnNum) {
    int startPredicatesNum = predicates.getStartPredicatesNum();
    int stopPredicatesNum = predicates.getStopPredicatesNum();
    
    //if the start predicates are not the same as the stop predicates and
    //the number of columns is not equal the index columns, ignore it because
    //we cannot obtain routing objects based on this condition.
    if (startPredicatesNum != stopPredicatesNum
        || startPredicatesNum != columnNum) {
      return false;
    }
    return true;
  }
  
  /**
   * this method generates a temp conglomerate descriptor to satisfy the requirement of
   * the derby code when the partition columns are not the primary key and the corresponding
   * Global hash index 
   * @param columnPositions
   * @return
   */
  ConglomerateDescriptor getFakeConglomerateDescriptor(int[] columnPositions) {

    boolean[] isAscending = new boolean[columnPositions.length];
    for (int i = 0; i < columnPositions.length; ++i) {
      isAscending[i] = true;
    }
    DataDescriptorGenerator ddg = getLanguageConnectionContext()
        .getDataDictionary().getDataDescriptorGenerator();
    IndexRowGenerator indexRowGenerator = new IndexRowGenerator(
        GfxdConstants.LOCAL_HASH1_INDEX_TYPE, true, columnPositions,
        isAscending, columnPositions.length);
    ConglomerateDescriptor cd = ddg.newConglomerateDescriptor(-1, "temp", true,
        indexRowGenerator, false, null, null, null);

    return cd;
  }

  /**
   * this method determines if the where clause contains the partition columns
   * or the primary key columns if the partition columns are not the primary
   * key. There are several different kinds of columns need to be checked. The
   * first one is partition columns, then the primary key with the global hash
   * index. Finally, check the remaining columns with the global index.
   * 
   * This method works as follows: Given a PredicateList and an ordered columns,
   * it checks if input columns consists of the start key and stop key. If it
   * is, then order these columns based on the input columns; otherwise, the
   * where clause does not contains useful information to prune the nodes for
   * the procedure call.
   * 
   * suppose the table t(c1, c2, c3, c4) (c1 ,c2) is primary key and (c3, c4)
   * are partition columns.
   * 
   * There are several cases to be considered: 1. c1=? and c2=? 2. c3=? and c4=?
   * 3. (c1=? or c3=?) and (c2=? or c4=?) //no routing object will got obtained.
   * 4. (c1=? and c2=?) or (c3=? and c4=?) //It is possible to get the routing
   * objects, but how? Another question is on the semantic of the data aware
   * procedure if we cannot correctly obtain supposed routing object. what
   * should we do? Propagate the statement to all nodes or just local node? But
   * what's the data-aware really meaning? 5. (c1=? and c2=?) or (c1=? and c2=?)
   * this case seems that derby still cannot transform it IN like operator. It
   * means that users can only specify only one column with the OR operator. Is
   * it too restrictive? Frankly, for derby, it is fine because it always do the
   * correct things other than efficiency. However, for GemFireXD, it could be
   * not acceptable. But how to solve it?
   * 
   * Note:
   * 
   * @TODO yjing, in the future, if we support create global hash index on
   *       non-primary key and the where clause contains these columns, the
   *       routing objects can be obtained if no primary key or partition
   *       columns in the where clause.
   * @param optTable
   * @param cd
   * @param pushPreds
   * @param nonMatchingIndexScan
   * @param coveringIndexScan
   * @throws StandardException
   */
  //boolean nonMatchingIndexScan: using this index as the table scan and does not limit the search scope.
  //boolean coveringIndexScan  The ResultColumns contains the index columns.
  
  private void getUsefulPredicates(PredicateList predList,
      int[] baseColumnPositions, PredicateList actionPredicates) throws StandardException {

    int size = predList.size();
    Predicate[] usefulPredicates = new Predicate[size];
    int usefulCount = 0;

    /*
     * If we have a "useful" IN list probe predicate we will generate a
     * start/stop key for optTable of the form "col = <val>", where <val> is the
     * first value in the IN-list. Then during normal index multi- probing (esp.
     * as implemented by exec/MultiProbeTableScanResultSet) we will use that
     * start/stop key as a "placeholder" into which we'll plug the values from
     * the IN-list one at a time.
     *      
     */

    /*
     * * Create an array of useful predicates. Also, count how many * useful
     * predicates there are.
     */
    for (int index = 0; index < size; index++) {
      Predicate pred = (Predicate)predList.elementAt(index);
      ColumnReference indexCol = null;
      int indexPosition;
      RelationalOperator relop = pred.getRelop();

      /*
       * InListOperatorNodes, while not relational operators, may still be
       * useful. There are two cases: a) we transformed the IN-list into a probe
       * predicate of the form "col = ?", which can then be optimized/generated
       * as a start/stop key and used for "multi- probing" at execution; or b)
       * we did *not* transform the IN-list, in which case we'll generate
       * _dynamic_ start and stop keys in order to improve scan performance
       * (beetle 3858). In either case the IN-list may still prove "useful".
       */
      InListOperatorNode inNode = pred.getSourceInList();
      boolean isIn = (inNode != null);

      /*
       * If it's not an "in" operator and either a) it's not a relational
       * operator or b) it's not a qualifier, then it's not useful for limiting
       * the scan, so skip it.
       */
      if (!isIn && ((relop == null) || !relop.isQualifier(this.table, true))) {
        continue;
      }

      /* Look for an index column on one side of the relop */
      for (indexPosition = 0; indexPosition < baseColumnPositions.length; indexPosition++) {
        if (isIn) {
          if (inNode.getLeftOperand() instanceof ColumnReference) {
            indexCol = (ColumnReference)inNode.getLeftOperand();
            if ((this.table.getTableNumber() != indexCol.getTableNumber())
                || (indexCol.getColumnNumber() != baseColumnPositions[indexPosition])
                || inNode.selfReference(indexCol))
              indexCol = null;
            else if (pred.isInListProbePredicate() && (indexPosition > 0)) {
              /*
               * If the predicate is an IN-list probe predicate then we only
               * consider it to be useful if the referenced column is the
               * *first* one in the index (i.e. if (indexPosition == 0)).
               * Otherwise the predicate would be treated as a qualifier for
               * store, which could lead to incorrect results.
               */
              indexCol = null;
            }
          }
        }
        else {
          indexCol = relop.getColumnOperand(this.table,
              baseColumnPositions[indexPosition]);
        }
        if (indexCol != null)
          break;
      }

      /*
       * * Skip over it if there is no index column on one side of the *
       * operand.
       */
      if (indexCol == null) {
        /*
         * If we're pushing predicates then this is the last time we'll get here
         * before code generation. So if we have any IN-list probe predicates
         * that are not useful, we'll need to "revert" them back to their
         * original IN-list form so that they can be generated as regular
         * IN-list restrictions. That "revert" operation happens in the
         * generateExpression() method of BinaryOperatorNode.
         */
        continue;
      }

      pred.setIndexPosition(indexPosition);

      /* Remember the useful predicate */
      usefulPredicates[usefulCount++] = pred;
    }

    /*
     * We can end up with no useful predicates with a force index override -
     * Each predicate is on a non-key column or both sides of the operator are
     * columns from the same table. There's no predicates to push down, so
     * return and we'll evaluate them in a PRN.
     */
    if (usefulCount == 0)
      return;

    /* The array of useful predicates may have unused slots. Shrink it */
    if (usefulPredicates.length > usefulCount) {
      Predicate[] shrink = new Predicate[usefulCount];

      System.arraycopy(usefulPredicates, 0, shrink, 0, usefulCount);

      usefulPredicates = shrink;
    }

    /* Sort the array of useful predicates in index position order */
    java.util.Arrays.sort(usefulPredicates);

    /* Push the sorted predicates down to the Optimizable table */
    int currentStartPosition = -1;
    boolean gapInStartPositions = false;
    int currentStopPosition = -1;
    boolean gapInStopPositions = false;
    boolean seenNonEquals = false;
    int firstNonEqualsPosition = -1;
    int lastStartEqualsPosition = -1;

    /*
     * beetle 4572. We need to truncate if necessary potential multi-column
     * start key up to the first one whose start operator is GT, and make start
     * operator GT; or start operator is GE if there's no such column. We need
     * to truncate if necessary potential multi-column stop key up to the first
     * one whose stop operator is GE, and make stop operator GE; or stop
     * operator is GT if no such column. eg., start key (a,b,c,d,e,f), potential
     * start operators (GE,GE,GE,GT,GE,GT) then start key should really be
     * (a,b,c,d) with start operator GT.
     */
    boolean seenGE = false, seenGT = false;

    for (int i = 0; i < usefulCount; i++) {
      Predicate thisPred = usefulPredicates[i];
      int thisIndexPosition = thisPred.getIndexPosition();
      boolean thisPredMarked = false;
      RelationalOperator relop = thisPred.getRelop();
      int thisOperator = -1;

      boolean isIn = (thisPred.getSourceInList() != null);

      if (relop != null)
        thisOperator = relop.getOperator();

      /* Allow only one start and stop position per index column */
      if (currentStartPosition != thisIndexPosition) {
        /*
         * * We're working on a new index column for the start position. * Is it
         * just one more than the previous position?
         */
        if ((thisIndexPosition - currentStartPosition) > 1) {
          /*
           * * There's a gap in the start positions. Don't mark any * more
           * predicates as start predicates.
           */
          gapInStartPositions = true;
        }
        else if ((thisOperator == RelationalOperator.EQUALS_RELOP)
            || (thisOperator == RelationalOperator.IS_NULL_RELOP)) {
          /*
           * Remember the last "=" or IS NULL predicate in the start position.
           * (The sort on the predicates above has ensured that these predicates
           * appear 1st within the predicates on a specific column.)
           */
          lastStartEqualsPosition = thisIndexPosition;
        }

        if (!gapInStartPositions) {
          /*
           * * There is no gap in start positions. Is this predicate * useful as
           * a start position? This depends on the * operator - for example,
           * indexCol = <expr> is useful, * while indexCol < <expr> is not
           * useful with asc index * we simply need to reverse the logic for
           * desc indexes * * The relop has to figure out whether the index
           * column * is on the left or the right, so pass the Optimizable *
           * table to help it.
           */
          if (!seenGT
              && (isIn || (relop.usefulStartKey(this.table)))) {
            thisPred.markStartKey();
            currentStartPosition = thisIndexPosition;
            thisPredMarked = true;
            seenGT = (thisPred.getStartOperator(this.table) == ScanController.GT);
          }
        }
      }

      /* Same as above, except for stop keys */
      if (currentStopPosition != thisIndexPosition) {
        if ((thisIndexPosition - currentStopPosition) > 1) {
          gapInStopPositions = true;
        }

        if (!gapInStopPositions) {
          if (!seenGE
              && (isIn || (relop.usefulStopKey(this.table)))) {
            thisPred.markStopKey();
            currentStopPosition = thisIndexPosition;
            thisPredMarked = true;
            seenGE = (thisPred.getStopOperator(this.table) == ScanController.GE);
          }
        }
      }

      /*
       * Mark this predicate as a qualifier if it is not a start/stop position
       * or if we have already seen a previous column whose RELOPS do not
       * include "=" or IS NULL. For example, if the index is on (a, b, c) and
       * the predicates are a > 1 and b = 1 and c = 1, then b = 1 and c = 1 also
       * need to be a qualifications, otherwise we may match on (2, 0, 3).
       */
      if ((!isIn) && // store can never treat "in" as qualifier
          ((!thisPredMarked) || (seenNonEquals && thisIndexPosition != firstNonEqualsPosition))) {
        thisPred.markQualifier();
      }

      /* Remember if we have seen a column without an "=" */
      if (lastStartEqualsPosition != thisIndexPosition
          && firstNonEqualsPosition == -1
          && (thisOperator != RelationalOperator.EQUALS_RELOP)
          && (thisOperator != RelationalOperator.IS_NULL_RELOP)) {
        seenNonEquals = true;
        /* Remember the column */
        firstNonEqualsPosition = thisIndexPosition;
      }

      //we do not have interest in the qualifier predicate.
      if (!thisPredMarked || thisPred.isQualifier()) {
        break;
      }
      actionPredicates.addPredicate(thisPred);

    }
  }

}
