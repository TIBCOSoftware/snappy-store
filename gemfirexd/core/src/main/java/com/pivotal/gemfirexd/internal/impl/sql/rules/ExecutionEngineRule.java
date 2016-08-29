package com.pivotal.gemfirexd.internal.impl.sql.rules;

import java.util.List;

import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;


abstract public class ExecutionEngineRule {
  private QueryInfo qInfo;
  private Boolean executionEngineFound = false;

  public enum ExecutionEngine {
    SPARK,
    STORE,
    NOT_DECIDED
  }


  public ExecutionEngineRule applyRule(DMLQueryInfo qInfo) {

    if (executionEngineFound) {
      return this;
    }

    executionEngineFound = findExecutionEngine(qInfo);

    if (! executionEngineFound) {
      List<SubQueryInfo> subqueries = qInfo.getSubqueryInfoList();
      if (subqueries.size() > 0) {
        for (DMLQueryInfo subquery : subqueries) {
          applyRule(subquery);
        }
      }
    }

    return this;
  }

  abstract protected boolean findExecutionEngine(DMLQueryInfo qInfo);
  public abstract ExecutionEngine getExecutionEngine();

}
