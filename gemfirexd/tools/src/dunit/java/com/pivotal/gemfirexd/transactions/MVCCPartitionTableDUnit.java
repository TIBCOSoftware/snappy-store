package com.pivotal.gemfirexd.transactions;

/**
 * Created by sachin on 27/4/17.
 */
public class MVCCPartitionTableDUnit extends MVCCDUnit {

    public MVCCPartitionTableDUnit(String name) {
        super(name);
    }


    @Override
    public String getSuffix() {
        return "partition_by column(intcol) ";
    }
}