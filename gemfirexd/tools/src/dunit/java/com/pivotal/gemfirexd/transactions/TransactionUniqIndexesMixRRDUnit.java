package com.pivotal.gemfirexd.transactions;

import java.sql.Connection;

public class TransactionUniqIndexesMixRRDUnit extends TransactionUniqIndexesMixDUnit {

  public TransactionUniqIndexesMixRRDUnit(String name) {
    super(name);
  }

  @Override
  protected int getIsolationLevel() {
    return Connection.TRANSACTION_REPEATABLE_READ;
  }
}
