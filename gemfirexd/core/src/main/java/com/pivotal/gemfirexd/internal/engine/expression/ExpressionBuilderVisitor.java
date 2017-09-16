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

package com.pivotal.gemfirexd.internal.engine.expression;

import java.util.ArrayList;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.compile.GemFireExpressionClassBuilder;
import com.pivotal.gemfirexd.internal.engine.sql.compile.types.DVDSet;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ClassName;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.MethodBuilder;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.VisitorAdaptor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.QueryTreeNodeVector;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultColumn;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.VirtualColumnNode;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;


/**
 * Builds an expression class from a ValueNode.
 * 
 * It generates methods based on each expression in the RCL.
 * 
 * @author soubhikc
 */
public class ExpressionBuilderVisitor extends VisitorAdaptor {
  
  private final CompilerContext cc;
  private GemFireExpressionClassBuilder generatingClass;
  private MethodBuilder resultExprFun;
  private final ArrayList<String> expressionMethodList;
  private GeneratedClass ac;
  private boolean skipChildren;

  public ExpressionBuilderVisitor(LanguageConnectionContext lcc) 
     throws StandardException {

    cc = (CompilerContext)lcc.getContextManager().getContext(CompilerContext.CONTEXT_ID); 

    expressionMethodList = new ArrayList<String> ();
  }

  private void createClass() 
    throws StandardException 
  {
    generatingClass = new GemFireExpressionClassBuilder( ClassName.BaseActivation, 
        null, 
        cc);
  }

  public boolean skipChildren(Visitable node) throws StandardException {
    if( QueryTreeNodeVector.class.isInstance(node) ) {
      /*
       * skip children for RC(s) in the list. As we
       * are returning 'false', RCL's children will 
       * be traversed but not any further.
       */
      skipChildren();
      return false;
    }
    
    return skipChildren;
  }

  public boolean stopTraversal() {
    return false;
  }

  public void dontSkipChildren() {
    skipChildren = false;
  }
  
  public void skipChildren() {
    skipChildren = true;
  }

  private static class SubqueryVisitor extends VisitorAdaptor {
    SubqueryNode result;

    /**
     * {@inheritDoc}
     */
    @Override
    public Visitable visit(Visitable node) throws StandardException {
      if (node instanceof SubqueryNode) {
        result = (SubqueryNode)node;
      }
      return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean stopTraversal() {
      return result != null;
    }
  }

  public Visitable visit(Visitable node) throws StandardException {

    // check for Subquery which is unsupported for this expression builder
    SubqueryVisitor visitor = new SubqueryVisitor();
    node.accept(visitor);
    if (visitor.result != null) {
      // unsupported case of subquery in a group by node
      throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
          "Subquery within GROUP BY context");
    }

    if(! (node instanceof ValueNode)) {
      return node;
    }

    ValueNode vn = null;
    ResultColumn rc = null;
    
    if( node instanceof ResultColumn) {
       rc = ((ResultColumn)node);
       
       if(! rc.isExpanded() ) {
         expressionMethodList.add(null);
         return node;
       }
       
       vn = rc.getExpression();
    }
    else {
       vn = (ValueNode) node;
    }
    
    if(generatingClass == null) {
      createClass();
    }
      
    vn.accept(new AlignToResultSetVisitor(0));
    generateExpression(vn);
    return node;
  }
  
  private void generateExpression(ValueNode source) 
     throws StandardException 
  {
    try {
      
        resultExprFun = generatingClass.getNewExpressionMethodBuilder();
        
        source.generateExpression(generatingClass, resultExprFun);
        resultExprFun.cast(ClassName.DataValueDescriptor);
        resultExprFun.methodReturn();
        resultExprFun.complete();
        
        expressionMethodList.add(resultExprFun.getName());
        
    }
    finally {
      
      if(SanityManager.DEBUG) {
        
        if(SanityManager.DEBUG_ON("DumpClassFile")) {
            SanityManager.DEBUG_PRINT("DumpClassFile", " Expression Class [" + 
                                            generatingClass.getClass().getName() + 
                                            "] generated method " + resultExprFun.getName() + 
                                            " for " + source);
        }
      }
      
    }
    
  }
  
  public ArrayList<String> getMethodsGenerated() {
    return expressionMethodList;    
  }

  public GeneratedClass getExpressionClass() throws StandardException {
    if (generatingClass == null) {
      return null;
    }

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceGroupByIter) {
        SanityManager.DEBUG_SET("DumpClassFile");
        SanityManager.DEBUG_SET("ClassLineNumbers");
      }
    }

    generatingClass.finishConstruct();
    ac = generatingClass.getGeneratedClass();

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceGroupByIter) {
        SanityManager.DEBUG_PRINT("DumpClassFile", " Expression Class ["
            + generatingClass.getClass().getName() + "] ");
        generatingClass.getClassBytecode();

        SanityManager.DEBUG_CLEAR("DumpClassFile");
        SanityManager.DEBUG_CLEAR("ClassLineNumbers");
      }
    }
    return ac;
  }
}

/**
 * This class updates the ResultSetNumber of the node
 * to incoming RS of the activation class's row[];
 * 
 * Also, for expressions having distinct aggregation this class
 * aligns the TypeService of the underlying RC of the column reference.
 * 
 * e.g select avg(distinct quantity) from testtable, 
 * 
 * @author soubhikc
 *
 */
class AlignToResultSetVisitor extends VisitorAdaptor {

  private final int resultSetNumber;
  
  public AlignToResultSetVisitor(int resultsetnumber) {
    this.resultSetNumber = resultsetnumber;
  }

  public boolean skipChildren(Visitable node) throws StandardException {
    return false;
  }

  public boolean stopTraversal() {
    return false;
  }

  public Visitable visit(Visitable node) throws StandardException {

    if( node instanceof ResultColumn) {
      ((ResultColumn)node).setResultSetNumber(this.resultSetNumber);
    }
    else if(node instanceof ColumnReference) {
        ColumnReference cr = (ColumnReference) node;
        ResultColumn rc = cr.getSource();
        DataTypeDescriptor rctype = rc.getType();

        if( rctype.getTypeId().getCorrespondingJavaTypeName()
             .equals(DVDSet.class.getName())
          ) 
        {
          cr.setSource(null);
          rc.setType( cr.getTypeServices() );
          cr.setSource(rc);
        }
        rc.accept(this);
    }
    else if(node instanceof VirtualColumnNode) {
       ((VirtualColumnNode)node).getSourceColumn().accept(this);
    }
    return node;
  }
}
