/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package cacheperf.comparisons.gemfirexd.tpcc.mapreduce;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.pivotal.gemfirexd.hadoop.mapred.Key;
import com.pivotal.gemfirexd.hadoop.mapred.Row;
import com.pivotal.gemfirexd.hadoop.mapred.RowInputFormat;
import com.pivotal.gemfirexd.hadoop.mapred.RowOutputFormat;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;

public class TPCCMapReduce extends Configured implements Tool {

  public static class TPCCMapper extends MapReduceBase implements Mapper<Key, Row, Text, MyRow> {

    @Override
    public void map(Key key, Row value, OutputCollector<Text, MyRow> output, Reporter reporter) throws IOException {
      
      String tableName = null;
      try {
        ResultSet rs = value.getRowAsResultSet();
        tableName = rs.getMetaData().getTableName(1);
        int c_id = rs.getInt("c_id");
        int c_d_id = rs.getInt("c_d_id");
        int c_w_id = rs.getInt("c_w_id");
        float c_balance = rs.getFloat("c_balance");
          
        System.out.println("mapper procesing record from " + tableName + ":" + c_w_id + "_" + c_d_id + "_" + c_id + " with c_balance = " + c_balance);
        if (c_balance > 0) {
          System.out.println("MAPPER FOUND RECORD " + c_w_id + "_" + c_d_id + "_" + c_id + " with c_balance = " + c_balance);
          Text myKey = new Text(c_w_id + "-" + c_d_id);
          MyRow myRow = new MyRow(c_w_id, c_d_id, c_balance);
          System.out.println("MAPPER writing intermediate record " + myRow.toString());
          output.collect(myKey, myRow);
        }
      } catch (SQLException se) {
        System.err.println("Error logging result set" + se);
      }
    }
  }

  public static class TPCCReducer extends MapReduceBase implements Reducer<Text, MyRow, Key, DataObject> {

    @Override
    public void reduce(Text key, Iterator<MyRow> values, OutputCollector<Key, DataObject> output, Reporter reporter) throws IOException {

      long sum = 0;
      int wid = -1;
      int did = -1;
      float balance = -1;
      while (values.hasNext()) {
        MyRow myRow = values.next();
        wid = myRow.getWarehouseId();
        did = myRow.getDistrictId();
        balance = myRow.getBalance();
        System.out.println("reducer processing record for " + myRow.toString());
        sum += balance;
      }
      DataObject o = new DataObject(wid, did, balance);
      System.out.println("reducer writing record " + o.toString());
      output.collect(new Key(), o);
    }
  }

  public static class MyRow implements Writable {
    private int w_id;               // warehouse id
    private int d_id;               // district id
    private float c_balance;        // customer balance

    public MyRow() {
    }

    public MyRow(int wid, int did, float balance) {
      w_id = wid;
      d_id = did;
      c_balance = balance;
    }
 
    public int getWarehouseId() {
      return this.w_id;
    }

    public int getDistrictId() {
      return this.d_id;
    }

    public float getBalance() {
      return this.c_balance;
    }

    public String toString() {
      return "warehouseId = " + w_id + " districtId = " + d_id + " customer balance = " + c_balance;
    }
    
    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(w_id);
      out.writeInt(d_id);
      out.writeFloat(c_balance);
    }
  
    @Override
    public void readFields(DataInput in) throws IOException {
      w_id = in.readInt();
      d_id = in.readInt();
      c_balance = in.readFloat();
    }
  }

  public static class DataObject {
    int warehouseId;
    int districtId;
    float districtBalance;

    public DataObject(int wid, int did, float balance) {
      warehouseId = wid;
      districtId = did;
      districtBalance = balance;
    }

    public void setWarehouseId(int index, PreparedStatement ps) throws SQLException {
      ps.setInt(index, warehouseId);
    }

    public void setDistrictId(int index, PreparedStatement ps) throws SQLException {
      ps.setInt(index, districtId);
    }

    public void setDistrictBalance(int index, PreparedStatement ps) throws SQLException {
      ps.setFloat(index, districtBalance);
    }

    public String toString() {
      return "warehouseId = " + warehouseId + " districtId = " + districtId + " totalBalance for district = " + districtBalance;
    }
  }

  public int run(String[] args) throws Exception {

    // todo@lhughes -- why do we need this?
    GfxdDataSerializable.initTypes();

    JobConf conf = new JobConf(getConf());
    conf.setJobName("tpccMapReduce");

    String hdfsHomeDir = args[0];
    String url         = args[1];
    String tableName   = args[2];

    System.out.println("TPCCMapReduce.run() invoked with " 
                       + " hdfsHomeDir = " + hdfsHomeDir 
                       + " url = " + url
                       + " tableName = " + tableName);

    // Job-specific params
    conf.set(RowInputFormat.HOME_DIR, hdfsHomeDir);
    conf.set(RowInputFormat.INPUT_TABLE, tableName);
    conf.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    
    conf.setInputFormat(RowInputFormat.class);
    conf.setMapperClass(TPCCMapper.class);
    conf.setMapOutputKeyClass(Text.class);
    conf.setMapOutputValueClass(MyRow.class);
    
    conf.setReducerClass(TPCCReducer.class);
    conf.set(RowOutputFormat.OUTPUT_TABLE, "HDFS" + tableName);
    //conf.set(GfxdOutputFormat.OUTPUT_SCHEMA, "APP");
    conf.set(RowOutputFormat.OUTPUT_URL, url);
    conf.setOutputFormat(RowOutputFormat.class);
    conf.setOutputKeyClass(Key.class);
    conf.setOutputValueClass(DataObject.class);

    StringBuffer aStr = new StringBuffer();
    aStr.append("HOME_DIR = " + conf.get(RowInputFormat.HOME_DIR) + " ");
    aStr.append("INPUT_TABLE = " + conf.get(RowInputFormat.INPUT_TABLE) + " ");
    aStr.append("OUTPUT_TABLE = " + conf.get(RowOutputFormat.OUTPUT_TABLE) + " ");
    aStr.append("OUTPUT_URL = " + conf.get(RowOutputFormat.OUTPUT_URL) + " ");
    System.out.println("TPCCMapReduce running with the following conf: " + aStr.toString());

    // not planning to use this, but I get an NPE without it
    FileOutputFormat.setOutputPath(conf, new Path("" + System.currentTimeMillis()));
    
    JobClient.runJob(conf);
    return 0;
  }
    
  public static void main(String[] args) throws Exception {
    System.out.println("TPCCMapReduce.main() invoked with " + args);
    int rc = ToolRunner.run(new TPCCMapReduce(), args);
    System.exit(rc);
  }
}
