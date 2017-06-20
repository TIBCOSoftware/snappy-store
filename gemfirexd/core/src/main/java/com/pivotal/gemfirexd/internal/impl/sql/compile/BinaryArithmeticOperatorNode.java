/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.compile.BinaryArithmeticOperatorNode

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package	com.pivotal.gemfirexd.internal.impl.sql.compile;

// GemStone changes BEGIN
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.AbstractRegion;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ColumnQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.PrunedExpressionQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoContext;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.expression.GfxdExprNodeVirtualIDVisitor;
// GemStone changes END

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ClassName;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.TypeCompiler;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.VisitorAdaptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.NumberDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ActivationClassBuilder;

import java.sql.Types;

import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

/**
 * This node represents a binary arithmetic operator, like + or *.
 *
 */

public final class BinaryArithmeticOperatorNode extends BinaryOperatorNode
{
	/**
	 * Initializer for a BinaryArithmeticOperatorNode
	 *
	 * @param leftOperand	The left operand
	 * @param rightOperand	The right operand
	 */
        private boolean isForAVG = false;

	public void init(
					Object leftOperand,
					Object rightOperand)
	{
		super.init(leftOperand, rightOperand,
				ClassName.NumberDataValue, ClassName.NumberDataValue);
	}
	
	public void init(
            Object leftOperand,
            Object rightOperand, Object isForAVG)
      {
         super.init(leftOperand, rightOperand,
         ClassName.NumberDataValue, ClassName.NumberDataValue);
         this.isForAVG = true;
         
       }

	public void setNodeType(int nodeType)
	{
		String operator = null;
		String methodName = null;

		switch (nodeType)
		{
			case C_NodeTypes.BINARY_DIVIDE_OPERATOR_NODE:
				operator = TypeCompiler.DIVIDE_OP;
				methodName = "divide";
				break;

			case C_NodeTypes.BINARY_MINUS_OPERATOR_NODE:
				operator = TypeCompiler.MINUS_OP;
				methodName = "minus";
				break;

			case C_NodeTypes.BINARY_PLUS_OPERATOR_NODE:
				operator = TypeCompiler.PLUS_OP;
				methodName = "plus";
				break;

			case C_NodeTypes.BINARY_TIMES_OPERATOR_NODE:
				operator = TypeCompiler.TIMES_OP;
				methodName = "times";
				break;

			case C_NodeTypes.MOD_OPERATOR_NODE:
				operator = TypeCompiler.MOD_OP;
				methodName = "mod";
				break;

			default:
				if (SanityManager.DEBUG)
				{
					SanityManager.THROWASSERT(
						"Unexpected nodeType = " + nodeType);
				}
		}
		setOperator(operator);
		setMethodName(methodName);
		super.setNodeType(nodeType);
	}

	/**
	 * Bind this operator
	 *
	 * @param fromList			The query's FROM list
	 * @param subqueryList		The subquery list being built as we find SubqueryNodes
	 * @param aggregateVector	The aggregate vector being built as we find AggregateNodes
	 *
	 * @return	The new top of the expression tree.
	 *
	 * @exception StandardException		Thrown on error
	 */

	public ValueNode bindExpression(
		FromList	fromList, SubqueryList subqueryList,
		Vector aggregateVector)
			throws StandardException
	{
		super.bindExpression(fromList, subqueryList,
				aggregateVector);

		TypeId	leftType = leftOperand.getTypeId();
		TypeId	rightType = rightOperand.getTypeId();
		DataTypeDescriptor	leftDTS = leftOperand.getTypeServices();
		DataTypeDescriptor	rightDTS = rightOperand.getTypeServices();

		/* Do any implicit conversions from (long) (var)char. */
		if (leftType.isStringTypeId() && rightType.isNumericTypeId())
		{
			boolean nullableResult;
			nullableResult = leftDTS.isNullable() ||
		 					 rightDTS.isNullable();
			/* If other side is decimal/numeric, then we need to diddle
			 * with the precision, scale and max width in order to handle
			 * computations like:  1.1 + '0.111'
			 */
			int precision = rightDTS.getPrecision();
			int scale	  = rightDTS.getScale();
			int maxWidth  = rightDTS.getMaximumWidth();

			if (rightType.isDecimalTypeId())
			{
				int charMaxWidth = leftDTS.getMaximumWidth();
				precision += (2 * charMaxWidth);								
				scale += charMaxWidth;								
				maxWidth = precision + 3;
			}

			leftOperand = (ValueNode)
					getNodeFactory().getNode(
						C_NodeTypes.CAST_NODE,
						leftOperand, 
						new DataTypeDescriptor(rightType, precision,
											scale, nullableResult, 
											maxWidth),
						getContextManager());
			((CastNode) leftOperand).bindCastNodeOnly();
		}
		else if (rightType.isStringTypeId() && leftType.isNumericTypeId())
		{
			boolean nullableResult;
			nullableResult = leftDTS.isNullable() ||
		 					 rightDTS.isNullable();
			/* If other side is decimal/numeric, then we need to diddle
			 * with the precision, scale and max width in order to handle
			 * computations like:  1.1 + '0.111'
			 */
			int precision = leftDTS.getPrecision();
			int scale	  = leftDTS.getScale();
			int maxWidth  = leftDTS.getMaximumWidth();

			if (leftType.isDecimalTypeId())
			{
				int charMaxWidth = rightDTS.getMaximumWidth();
				precision += (2 * charMaxWidth);								
				scale += charMaxWidth;								
				maxWidth = precision + 3;
			}

			rightOperand =  (ValueNode)
					getNodeFactory().getNode(
						C_NodeTypes.CAST_NODE,
						rightOperand, 
						new DataTypeDescriptor(leftType, precision,
											scale, nullableResult, 
											maxWidth),
						getContextManager());
			((CastNode) rightOperand).bindCastNodeOnly();
		}

		/*
		** Set the result type of this operator based on the operands.
		** By convention, the left operand gets to decide the result type
		** of a binary operator.
		*/
		setType(leftOperand.getTypeCompiler().
					resolveArithmeticOperation(
						leftOperand.getTypeServices(),
						rightOperand.getTypeServices(),
// GemStone changes BEGIN
						isForAVG() ? TypeCompiler.AVG_OP : operator
						/* (original code)
						operator
						*/
// GemStone changes END
							)
				);

		return this;
	}
// GemStone changes BEGIN

  // Asif:   Implementing the below method to fix the  parameter count anomaly( Bug 39646) and
  // to fix Bug 40857 ( related to nodes pruning using partition expression resolver )
  @Override
  public QueryInfo computeQueryInfo(final QueryInfoContext qic)
      throws StandardException {
    final TableDescriptor[] td = new TableDescriptor[1];

    final Visitor infoCollector = new VisitorAdaptor() {

      private boolean sameRegionExpression = true;

      public boolean skipChildren(Visitable node) throws StandardException {
        return false;
      }

      public boolean stopTraversal() {
        return !this.sameRegionExpression;
      }

      public Visitable visit(Visitable node) throws StandardException {
        if (node instanceof ColumnReference) {
          final ColumnQueryInfo cqi = (ColumnQueryInfo)((ColumnReference)node)
              .computeQueryInfo(qic);
          if (td[0] == null) {
            td[0] = cqi.getTableDescriptor();
          }
          else if (!cqi.getSchemaName().equals(td[0].getSchemaName())
              || !cqi.getTableName().equals(td[0].getName())) {
            td[0] = null;
            this.sameRegionExpression = false;
          }
        }
        return node;
      }
    };
    GfxdExprNodeVirtualIDVisitor visitor = new GfxdExprNodeVirtualIDVisitor(
        null, infoCollector, "DML");
    this.accept(visitor);
    final TableDescriptor desc = td[0];
    final Region<?, ?> rgn;
    if (desc == null) {
      return QueryInfoConstants.DUMMY;
    }
    else if ((rgn = Misc.getRegionByPath(desc, this.lcc, true)).getAttributes()
        .getDataPolicy().withPartitioning()) {
      final GfxdPartitionResolver spr = GemFireXDUtils.getResolver((AbstractRegion)rgn);
      if (spr == null) return QueryInfoConstants.DUMMY;
      final String canonicalStr = visitor.getCanonicalizedExpression();
      if (canonicalStr.equals(spr.getCanonicalizedExpression())) {
        return new PrunedExpressionQueryInfo(this.getTypeServices(),
            (AbstractRegion)rgn, spr, desc.getSchemaName(), desc.getName(),
            canonicalStr, td[0]);
      }
      else {
        return QueryInfoConstants.DUMMY;
      }
    }
    else {
      return QueryInfoConstants.DUMMY;
    }
  }

  @Override
  boolean isForAVG() {
    return isForAVG;
  }

// GemStone changes END

}
