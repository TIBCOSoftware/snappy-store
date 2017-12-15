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
package com.pivotal.gemfirexd.query;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import io.snappydata.test.dunit.DistributedTestBase;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.apache.log4j.Logger;

public class QueryEvaluationHelper {
  
  public static volatile boolean isQueryExecutedOnNode = false;
  @SuppressWarnings("unused")
  public static final int byrange = 0x01, bylist = 0x02, bycolumn = 0x04, ANDing = 0x08, ORing = 0x10, Contains = 0x20, Ordered = 0x40, TimesOccur = 0x80;
  @SuppressWarnings("unused")
  public static final int noCheck = 0x01, fixLater = noCheck|0x02, fixImmediate = noCheck|0x04, wontFix = noCheck|0x08, fixDepend = noCheck|0x10, 
                           fixInvestigate = noCheck|0x20;


  //////////////////////////////////////////////////////////////////////////////////////////////
  
    ///////////////////Helper methods ///////////////////
  
  @SuppressWarnings("unchecked")
  public static void evaluate4DQueryArray(Object[][][][] queries, VM[] vms, DistributedSQLTestBase test) throws Exception {
    
    List rslts = new ArrayList();
    Map verifyOutput = null;
    List valueOccurance = null;
    String log = new String();

    boolean verifyOutputFromArray = false;
    
      for( int qry=0; qry<queries.length; qry++) {

         final SelectQueryInfo[] sqiArr = new SelectQueryInfo[1];
         String queryString = ""; log = "\n";
         if( queries[qry][0][0][0] instanceof Integer ) {
           /* Enable this section of code for running outstanding fix for queries. 
            * Assign ORed values to fixableQueries.
           //change this flag to one or multiple flags like fixImmediate | fixDepend to run pending queries only.
           final int fixableQueries = fixImmediate;
           
           int execflags = ((Integer)queries[qry][0][0][0]).intValue();
           switch(execflags) {
             case (fixImmediate & fixableQueries): 
               log += "fixImmediate ";
               break;
             case (fixDepend & fixableQueries): 
               log += "fixDepend ";
               break;
             case (none & fixableQueries):
               break;
             default:
               getLogWriter().info( "Query " + (qry+1) + " with execflags " + execflags + " not executed 'coz fixableQueries are " + fixableQueries);
               continue;
           }
           queryString = (String)queries[qry][0][0][1];
           */
           continue;
         } 
         
         queryString = queryString.length()!=0 ? queryString :
                       (String)queries[qry][0][0][0];
         setupObservers( vms, sqiArr,queryString);
   
         TestUtil.jdbcConn.commit();
         
         
         EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn.prepareStatement(queryString);    

         log+="Query "+ (qry+1) + " : \""+eps.getSQLText() + "\"";
         
         for( int prm_1 = 0; prm_1 < queries[qry][1].length; prm_1++) {
           eps.setObject(prm_1+1, queries[qry][1][prm_1][1], ((Integer)queries[qry][1][prm_1][0]).intValue() );
           log+="\n - prm " + (prm_1+1) + " = " + queries[qry][1][prm_1][1].toString();
         }
         int rows = ((Integer)queries[qry][2][0][0]).intValue(), 
             noOfPrunedNodes = ((Integer)queries[qry][2][0][1]).intValue(), 
             noExecNodes = ((Integer)queries[qry][2][0][2]).intValue();
         
         log+="\n - expected " + rows + " rows, pruned to " + noOfPrunedNodes + " nodes, no executions on " + noExecNodes + " nodes";
         
         //check if any particular verification of output is there.
         verifyOutputFromArray = ( queries[qry][2].length > 1) ? true : false;
         
         if(verifyOutputFromArray) {
           verifyOutput = new LinkedHashMap<Integer, List<Object>>();
           for(int out=1; out < queries[qry][2].length; out++) {
             Object[] Values = (Object[])queries[qry][2][out][2];
             rslts = new Vector(Values.length);
             for (Object value : Values) {
               rslts.add( value );
             }
             verifyOutput.put(queries[qry][2][out][1]/*col idx in projection*/, rslts);
           }
         } else {
           for (int i = 1; i < 8; ++i) {
             rslts.add(Integer.valueOf(i));
           }
         }

         getLogWriter().info(log);
         ResultSet rs = eps.executeQuery();
         /*
          * ResultSet check
          */
         try {
         valueOccurance = new ArrayList< Map<Object, Integer> >(queries[qry][2].length);
         for( int rw = 1; rw <= rows; rw++) {
           DistributedTestBase.assertTrue("More rows are expected got " + rw + " required " + rows, rs.next());
           
           if(verifyOutputFromArray) {
             for(int out=1; out < queries[qry][2].length; out++) {
               int verificationType = ((Integer)queries[qry][2][out][0]).intValue();
               Integer colIdx = (Integer)queries[qry][2][out][1];
               
               rslts = (Vector<Object>)verifyOutput.get(colIdx);
               if ( (verificationType & 0xFFFF) == Contains) {
                 Object expected = rs.getObject(colIdx);
                 expected = (expected == null) ? new String(" null ") : expected;
                 getLogWriter().info("RS col[" + colIdx + "] : "
                     + "checking object " + expected.toString() + " contained in results " + rslts);
                 DistributedTestBase.assertTrue(rslts.contains( rs.getObject(colIdx) ));
                 
               }
               else if( (verificationType & 0xFFFF) == TimesOccur) {
                 //lets record the value occurances for colIdx here.
                 if(valueOccurance.size() <= out) {
                   for(int i=valueOccurance.size(); i<=out; i++)
                     valueOccurance.add(new LinkedHashMap<Object,Integer>());
                 }
                 
                 Map<Object,Integer> mapOfValues = (Map<Object,Integer>) valueOccurance.get(out);
                 assert mapOfValues != null;
                 
                 Object received = rs.getObject(colIdx);
                 Integer occurance = mapOfValues.get(received);
                 if( occurance == null ) {
                   mapOfValues.put(received, 1);
                 }
                 else {
                   mapOfValues.put(received, occurance+1);
                 }
                 getLogWriter().info(
                     "RS col["
                         + colIdx
                         + "] = "
                         + (rs.getObject(colIdx) != null ? rs.getObject(colIdx)
                             .toString() : "null") + " current map of values = "
                         + mapOfValues);
               }
               else if( (verificationType & 0xFFFF) == Ordered) {
                 Object expected = rslts.get(rw-1/*vector is zero based whereas RS is 1 based*/);
                 getLogWriter().info("RS col[" + colIdx + "] = " 
                     + (rs.getObject(colIdx)!=null?rs.getObject(colIdx).toString():"null") 
                     + " And expected " + (expected!=null?expected.toString():"null") );
                 if( expected != null ) {
                   if( expected instanceof Double ) {
                     Object received = rs.getObject(colIdx);
                     DistributedTestBase.assertTrue( "expected Double got " + received.getClass()
                                                    , received instanceof Double );
                     double recdDbl = ((Double)received).doubleValue();
                     double expcDbl = ((Double)expected).doubleValue();
                     double absDiff = java.lang.Math.abs(recdDbl - expcDbl);
                     /*
                     String msg = " recdDbl=" + recdDbl + " expcDbl=" + expcDbl + " Diff("+(recdDbl - expcDbl)+")" + " absDiff(" + absDiff + ") < 1.0e-4="+Boolean.valueOf(absDiff < 1.0e-4);
                     DistributedTestBase.assertTrue( msg, absDiff < 1.0e-4);
                     */
                     // tolerate upto .0005% difference
                    String msg = " recdDbl=" + recdDbl + " expcDbl=" + expcDbl
                        + " Diff(" + (recdDbl - expcDbl) + ")"
                        + " absDiff/expcDbl(" + (absDiff / expcDbl)
                        + ") < 1.0e-5="
                        + Boolean.valueOf((absDiff / expcDbl) < 1.0e-5);
                     DistributedTestBase.assertTrue( msg, (absDiff / expcDbl) < 1.0e-5);
                   }
                   else if( expected instanceof Float ) {
                     Object received = rs.getObject(colIdx);
                     DistributedTestBase.assertTrue( "expected Float got " + received.getClass()
                                                    , received instanceof Float );
                     float recdFl = ((Float)received).floatValue();
                     float expcFl = ((Float)expected).floatValue();
                     float absDiff = java.lang.Math.abs(recdFl - expcFl);
                     String msg = " recdFl=" + recdFl + " expcFl=" + expcFl + " Diff("+(recdFl - expcFl)+")" + " absDiff(" + absDiff + ") < 1.0e-3="+Boolean.valueOf(absDiff < 1.0e-3); 
                     DistributedTestBase.assertTrue( msg, absDiff < 1.0e-3);
                   }
                   else {
                      DistributedTestBase.assertEquals(expected.toString(), rs.getObject(colIdx).toString());
                   }
                 } else {
                   Object v = rs.getObject(colIdx);
                   DistributedTestBase.assertNull("expected null, got: " + v
                                                  + ";queryString=" + queryString
                                                  + ";colIdx=" + colIdx,
                                                  v);
                 }
               } //end of Ordered check
             } //end of columns check
           } else {
             getLogWriter().info("got object - " + rs.getObject("id").toString());
             DistributedTestBase.assertTrue(rslts.contains( rs.getObject("id") ));
           }
         } //end of rows check
         DistributedTestBase.assertFalse("No more rows should have existed, expected only " + rows, rs.next());
         } finally {
           rs.close();
         }
         getLogWriter().info("ValueOccurance map created for 'received entries' : " + valueOccurance);
         for(int out=1; out < queries[qry][2].length; out++) {
           int verificationType = ((Integer)queries[qry][2][out][0]).intValue();
           if( (verificationType & 0xFFFF) == TimesOccur) {
             getLogWriter().info("sb: checking for " + out);
             Map<Object,Integer> valueMap = (Map<Object,Integer>) valueOccurance.get(out);
             Object[] Values = (Object[])queries[qry][2][out][2];
             Integer[] exptdCounts = (Integer[])queries[qry][2][out][3];
             
             for( int outVal=0; outVal < Values.length; outVal++) {
               Integer recdCount = valueMap.get( Values[outVal] );
               getLogWriter().info("sb: " + Values[outVal] + " count recevied " + recdCount);
               assert recdCount != null: "recdCount cannot be null ";
               DistributedTestBase.assertTrue( "Value " + (Values[outVal]==null?"null":Values[outVal]) 
                                             + " exptd " + exptdCounts[outVal] + " recd " + recdCount, 
                                    recdCount.intValue() == exptdCounts[outVal].intValue() );
             }
             
           }
         }
         
         /*
          * Nodes Pruning check
          */
         if (vms.length > 1) {
           if ((((Integer)queries[qry][3][0][0]).intValue() & noCheck) != noCheck) {
             verifyExecutionOnDMs(sqiArr[0],
                 getPrunedDistributedMembers(queries[qry][3], sqiArr, test),
                 noOfPrunedNodes, noExecNodes, log, test);
           }
         }
         getLogWriter().info("Query " + (qry+1) + " : succeeded");
      } // end of for loop 
  }

  public static void setupObservers(VM[] dataStores, final SelectQueryInfo[] sqi, String queryStr) {
    // Create a prepared statement for the query & store the queryinfo so
    // obtained to get
    // region reference
    //final SelectQueryInfo[] sqi = _qi;    
    // set up a sql query observer in client VM
    GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qi,GenericPreparedStatement gps, LanguageConnectionContext lcc) {            
            sqi[0] = (SelectQueryInfo)qi;
          }
        });

    // set up a sql query observer in server VM to keep track of whether the
    // query got executed on the node or not
    SerializableRunnable setObserver = new SerializableRunnable(
        "Set GemFireXDObserver on DataStore Node for query " + queryStr) {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void beforeQueryExecutionByPrepStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper,
                    EmbedPreparedStatement pstmt, String query) {
                  getLogWriter().info("Observer::" +
                      "beforeQueryExecutionByPrepStatementQueryExecutor invoked");
                  isQueryExecutedOnNode = true;
                }
                @Override
                public void beforeQueryExecutionByStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper, EmbedStatement stmt,
                    String query) {
                  isQueryExecutedOnNode = true;
                }
              });
        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    
    for (VM dataStore : dataStores) {
      dataStore.invoke(setObserver);
    }
  }

  public static void setObserver(VM[] dataStores, String node, String queryStr, final GemFireXDQueryObserver queryObserver) {
    
    SerializableRunnable setObserver = new SerializableRunnable(
        "Set GemFireXDObserver on "+node+" Node for query " + queryStr) {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(queryObserver);
        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    for (VM dataStore : dataStores) {
      if(dataStore == null) continue;
      dataStore.invoke(setObserver);
    }
    
  }
  /**
   * 
   * @param sqi
   * @param routingObjects
   * @param noOfPrunedNodes
   * @param noOfNoExecQueryNodes  Query shouldn't get executed on exculding client nodes.
   */
  @SuppressWarnings("unused")
  public static void verifyQueryExecution(final SelectQueryInfo sqi, Object[] routingObjects, int noOfPrunedNodes, int noOfNoExecQueryNodes, String query
      ,DistributedSQLTestBase test) {
    PartitionedRegion pr = (PartitionedRegion)sqi.getRegion();
    Set prunedNodes = (routingObjects.length!=0?pr.getMembersFromRoutingObjects(routingObjects):new HashSet());
    getLogWriter().info("Number of members found after prunning ="+prunedNodes.size());
    verifyExecutionOnDMs(sqi, prunedNodes, noOfPrunedNodes, noOfNoExecQueryNodes,query, test);
  }

  public static void verifyExecutionOnDMs(final SelectQueryInfo sqi, Set<DistributedMember> prunedNodes, int noOfPrunedNodes, int noOfNoExecQueryNodes, String query
      ,final DistributedSQLTestBase test) {

    SerializableRunnable validateQueryExecution = new SerializableRunnable(
        "validate node has executed the query " + query) {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter());
          DistributedSQLTestBase.assertTrue(isQueryExecutedOnNode);
          isQueryExecutedOnNode = false;

        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    SerializableRunnable validateNoQueryExecution = new SerializableRunnable(
        "validate node has NOT executed the query " + query) {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter());
          DistributedSQLTestBase.assertFalse(isQueryExecutedOnNode);
          isQueryExecutedOnNode = false;

        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    PartitionedRegion pr = (PartitionedRegion)sqi.getRegion();
    Set<InternalDistributedMember> nodesOfPr =  pr.getRegionAdvisor().adviseDataStore();
    getLogWriter().info(" Total members = " + nodesOfPr.size());
    
    String logPrunedMembers = new String();
    logPrunedMembers = " Prunned member(s) " + prunedNodes.size() + "\n";
    for( DistributedMember dm : prunedNodes ) {
      if( dm != null) {
        logPrunedMembers += dm.toString() + " ";
      }
    }
    getLogWriter().info(logPrunedMembers);
    if( noOfPrunedNodes  > -1)
      DistributedSQLTestBase.assertEquals(noOfPrunedNodes, prunedNodes.size());
    
    nodesOfPr.removeAll(prunedNodes);   
    String logNonPrunedMembers = new String();
    logNonPrunedMembers = " Non-prunned member(s) " + nodesOfPr.size() + "\n";
    for(DistributedMember dm : nodesOfPr) {
      if( dm != null) {
        logNonPrunedMembers += dm.toString() + " ";
      }
    }
    getLogWriter().info(logNonPrunedMembers);
if(noOfNoExecQueryNodes > -1)
  DistributedSQLTestBase.assertEquals(noOfNoExecQueryNodes,nodesOfPr.size());
    
    //Nodes not executing the query 
    for( DistributedMember memberNoExec : nodesOfPr) {
      VM nodeVM = test.getHostVMForMember(memberNoExec);
      DistributedSQLTestBase.assertNotNull(nodeVM);
      getLogWriter().info("Checking non-execution on VM(pid) : " + nodeVM.getPid() );
      nodeVM.invoke(validateNoQueryExecution);
    }
    
    
    //Nodes executing the query
    for( DistributedMember memberExec : prunedNodes) {
      VM nodeVM = test.getHostVMForMember(memberExec);
      DistributedSQLTestBase.assertNotNull(nodeVM);
      getLogWriter().info("Checking execution on VM(pid) : " + nodeVM.getPid());
        nodeVM.invoke(validateQueryExecution);
    }
    
  }
  
  public static Set<DistributedMember> getPrunedDistributedMembers(Object[][] routingInfo, SelectQueryInfo[] sqiArr, final DistributedSQLTestBase test) {
    //Identyify all the nodes of the PR
    PartitionedRegion pr = (PartitionedRegion)sqiArr[0].getRegion();
    //Identify the nodes which should see the query based on partition resolver
    GfxdPartitionResolver spr = (GfxdPartitionResolver)pr.getPartitionResolver();
    Set AllNodesOfPr =  pr.getRegionAdvisor().adviseDataStore();
    Set<InternalDistributedMember> currentDMs = null;
    Set<DistributedMember> prunedNodes = new HashSet<DistributedMember>();
    
    boolean isRange = (((Integer)routingInfo[0][0]).intValue() & 0xFFFF) == byrange;

    for(int i = 0; i < routingInfo.length; i++) {
      
      Object[] currentKeys = null;
      
      if(isRange) {
        DataValueDescriptor lowerbound = (DataValueDescriptor)routingInfo[i][1]; 
        DataValueDescriptor upperbound = (DataValueDescriptor)routingInfo[i][3];
         
        boolean lboundinclusive = ((Boolean)routingInfo[i][2]).booleanValue(),
                uboundinclusive = ((Boolean)routingInfo[i][4]).booleanValue();
        
        currentKeys = spr.getRoutingObjectsForRange(lowerbound, lboundinclusive, upperbound, uboundinclusive);
        currentKeys = (currentKeys!=null? currentKeys: new Object[]{} );
        
        //for logging purpose only replacing the nulls.
        String lbound = (lowerbound == null? new String("null"): lowerbound.toString());
        String ubound = (upperbound == null? new String("null"): upperbound.toString());
        getLogWriter().info("Range " + (i+1) + " ["+lbound+" to "+ubound+"] : routing keys = "+currentKeys.length);
      }
      
      for(Object entry : currentKeys) {
        getLogWriter().info("   routing keys = [" + entry.toString() + "]");
      }
      currentDMs = (currentKeys.length!=0 ? pr.getMembersFromRoutingObjects(currentKeys) : AllNodesOfPr);
      DistributedSQLTestBase.assertEquals( ((Integer)routingInfo[i][5]).intValue(), currentDMs.size());
      
      if(i == 0) {
        prunedNodes.addAll(currentDMs);
        getLogWriter().info("Starting members with: " + prunedNodes.size() + " elements " + prunedNodes.toString() );
      } else if( (((Integer)routingInfo[i][0]).intValue() & 0xFFFF) == ANDing) {
        prunedNodes.retainAll(currentDMs);
        getLogWriter().info("ANDing resulted list to " + prunedNodes.size() + " elements " + prunedNodes.toString() );
      } else if( (((Integer)routingInfo[i][0]).intValue() & 0xFFFF) == ORing) {
        prunedNodes.addAll(currentDMs);
        getLogWriter().info("ORing resulted list to " + prunedNodes.size() + " elements " + prunedNodes.toString() );
      }      
      
    }
//    test.assertFalse(routingObjects1[0] == routingObjects2[0]);
    
    return prunedNodes;
  }
  
  public static void evaluateQueriesForListPartitionResolver(Object[][] queries, VM[] vms, DistributedSQLTestBase test) 
  throws Exception {
 
   List rslts = new ArrayList();
   String log = new String();
   //final SelectQueryInfo[] sqi = new SelectQueryInfo[1];    
   SelectQueryInfo sqiArray[] = new SelectQueryInfo[1];
   String queryString = (String)queries[0][0];
   setupObservers( vms, sqiArray,queryString);
   
   ResultSet rs = null;
   PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(queryString);
   EmbedPreparedStatement eps = (EmbedPreparedStatement)ps;
   log="\nexecuting query "+eps.getSQLText();
   
   DataValueDescriptor[] dvdarr = new DataValueDescriptor[queries.length-2];
   for( int prm_1 = 1; prm_1 < queries.length-1; prm_1++) {
     ps.setObject(prm_1, ((DataValueDescriptor)queries[prm_1][1]).getObject(), ((Integer)queries[prm_1][0]).intValue() );
     rslts.add( ((DataValueDescriptor)queries[prm_1][1]).getObject() );
     dvdarr[prm_1-1] = (DataValueDescriptor)queries[prm_1][1];
     //dvdarr.add((DataValueDescriptor)queries[prm_1][1]);
     log+=" - prm" + prm_1 + " = " + queries[prm_1][1].toString();
   }
   int rows = ((Integer)queries[queries.length-1][0]).intValue(); 
   log+=" - expected " + rows + " rows";
  
   getLogWriter().info(log);
   
   rs = ps.executeQuery();
   for( ; rows > 0; rows--) {
      DistributedTestBase.assertTrue(rs.next());
      getLogWriter().info("got object - " + rs.getObject("id").toString());
      DistributedTestBase.assertTrue(rslts.contains( rs.getObject("id") ));
   }
   DistributedTestBase.assertFalse(rs.next());
   //Identify all the nodes of the PR
   PartitionedRegion pr = (PartitionedRegion)sqiArray[0].getRegion();
   //Identify the nodes which should see the query based on partition resolver
   GfxdPartitionResolver spr = (GfxdPartitionResolver)pr.getPartitionResolver();
   Object[] routingObjects = spr.getRoutingObjectsForList(dvdarr);
   verifyQueryExecution(sqiArray[0], routingObjects, ((Integer)queries[queries.length-1][1]).intValue(), ((Integer)queries[queries.length-1][2]).intValue(), log, test);
   
   rslts.clear();
   rs.close();
  }

  public static Logger getLogWriter() {
    return TestUtil.getLogger();
  }

  public static void reset() {
    isQueryExecutedOnNode = false;
  }
}
