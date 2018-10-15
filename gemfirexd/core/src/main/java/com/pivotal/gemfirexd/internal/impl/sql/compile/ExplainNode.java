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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
package com.pivotal.gemfirexd.internal.impl.sql.compile;

import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;

import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoContext;
import com.pivotal.gemfirexd.internal.engine.reflect.SnappyActivationClass;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ClassName;
import com.pivotal.gemfirexd.internal.iapi.services.classfile.VMOpcode;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.MethodBuilder;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

/**
 * Support for explain command that will return a iapi.ResultSet as output
 * constituting the query execution plan.
 * 
 * @author soubhikc
 * 
 */
public class ExplainNode extends DMLStatementNode {

  private String userQuery;
  
  private ArrayList<ArrayList<Object>> userQueryParameters;
  
  private PreparedStatement userQueryStatement;
  
  private XPLAINUtil.XMLForms xmlForm;
  private CharConstantNode embedXslFileName;

  private ResultColumnList resultColumnsList;

  private static ResultColumnDescriptor[] columnInfo;

  private static ResultSetMetaData metadata;
  
  private boolean generateParameterValueSet = false;

  static {
    if (metadata == null) {
      columnInfo = new ResultColumnDescriptor[1];
//      columnInfo[0] = EmbedResultSetMetaData.getResultColumnDescriptor(
//          "MEMBER_ID", DataTypeDescriptor.getBuiltInDataTypeDescriptor(
//              Types.VARCHAR, false, 128));
      columnInfo[0] = EmbedResultSetMetaData.getResultColumnDescriptor(
          "plan", DataTypeDescriptor.getBuiltInDataTypeDescriptor(
              Types.CLOB, false));

      metadata = new EmbedResultSetMetaData(columnInfo);
    }
  }

  @SuppressWarnings("unchecked")
  public void init(Object queryParsedTree, Object queryParameters,
      Object xmlForms, Object xslFileName) {
    this.userQuery = (String)queryParsedTree;
    this.userQueryParameters = (ArrayList<ArrayList<Object>>) queryParameters;
    this.xmlForm = (XPLAINUtil.XMLForms)xmlForms;
    this.embedXslFileName = (CharConstantNode) xslFileName;
  }

  public void bindStatement() throws StandardException {

    resultColumnsList = (ResultColumnList)getNodeFactory().getNode(
        C_NodeTypes.RESULT_COLUMN_LIST, getContextManager());

    TableName tab = (TableName)getNodeFactory().getNode(C_NodeTypes.TABLE_NAME,
        null, "EXPLAIN_DATA", getContextManager());

    resultColumnsList.createListFromResultSetMetaData(metadata, tab, this
        .getClass().getName());
    
    if (XPLAINUtil.getStatementType(userQuery) != null) {
      userQueryStatement = getLanguageConnectionContext().prepareInternalStatement(this.userQuery,
          getCompilerContext().getOriginalExecFlags());
      
      final DataTypeDescriptor[] parameterTypes = userQueryStatement.getParameterTypes();
      if (userQueryParameters == null && parameterTypes != null && parameterTypes.length > 0) {
        assert getCompilerContext().getParameterTypes() != null && getCompilerContext().getParameterTypes().length == parameterTypes.length;
        
        for (int i = 0; i < parameterTypes.length; i++) {
          getCompilerContext().getParameterTypes()[i] = parameterTypes[i];
        }
        
        generateParameterValueSet = true;
      }
      // userQueryStatement = null;
    }
  }

  public void optimizeStatement() throws StandardException {
  }

  public boolean isRouted() throws StandardException {
    return userQueryStatement instanceof ExecPreparedStatement &&
        ((ExecPreparedStatement)userQueryStatement).getActivationClass()
            instanceof SnappyActivationClass;
  }

  public QueryInfo computeQueryInfo(QueryInfoContext qic)
      throws StandardException {
    return null;
  }

  protected void generate(ActivationClassBuilder acb, MethodBuilder mb)
      throws StandardException {

    userQueryStatement = null;

    acb.pushGetResultSetFactoryExpression(mb);

    acb.pushThisAsActivation(mb); // arg 1

    // get a function to allocate plan rows of the right shape and size
    resultColumnsList.generateHolder(acb, mb); // arg 2

    mb.push(userQuery); // arg 3
    
    mb.push(acb.addItem(userQueryParameters)); // arg 4

    mb.push(xmlForm.ordinal()); // arg 5
    
    if (embedXslFileName != null) {
      mb.push(embedXslFileName.getValue().getString()); // arg 6
    }
    else {
      mb.pushNull(String.class.getName());
    }
    
    mb.callMethod(VMOpcode.INVOKEINTERFACE, (String)null,
        "getExplainResultSet", ClassName.NoPutResultSet, 6);

    if (generateParameterValueSet) {
      generateParameterValueSet(acb);    
    }
  }

  public ResultDescription makeResultDescription() {
    ResultColumnDescriptor[] colDescs = resultColumnsList
        .makeResultDescriptors();
    String statementType = statementToString();

    return getExecutionFactory().getResultDescription(colDescs, statementType);
  }

  @Override
  public String statementToString() {
    return "EXPLAIN";
  }

}
