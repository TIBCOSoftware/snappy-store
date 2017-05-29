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
package com.pivotal.gemfirexd.internal.engine.distributed.metadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;



import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.internal.cache.AbstractRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.catalog.IndexDescriptor;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultColumn;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.VirtualColumnNode;

/**
 * This class represents a Column . An object of this class gets created as part of creating QueryInfo object
 *  during where clause , from clause or projection attribute evaluation 
 * @author Asif
 * @since GemFireXD
 *
 */
public class ColumnQueryInfo extends AbstractQueryInfo implements AbstractColumnQueryInfo {
  final private String exposedColumnName;
  final private int virtualColumnNumber;
  final private String actualColumnName;
  final private int actualColumnNumber;
  final DataTypeDescriptor columnType;
  // always  non-null. This this is not actually a column, then the
  // columnDescriptor will be an instance of the private inner class
  // NonColumnDescriptor
  final ColumnDescriptor columnDescriptor;
  private final TableDescriptor td;
  private int partitioningColPos = -1;

  //TODO:Asif: We should rather have a specialized class which extends
  //ColumnQueryInfo & instance of that object be created only for
  //ComparisonQueryInfo  which actually have use of TableDescriptor.
  //Or we should uniformly store TableDescriptor in this class & remove
  // other fields from this class which can be obtained from
  // table descriptor
  private TableQueryInfo tqi;
  private int tableNum = -1;
  private GfxdPartitionResolver resolver =null;
  private boolean isOuterScope = false;
  /**
   * This constructor is used during from AndNode during where clause evaluation
   * to create QueryInfo Object    
   * @param cr ColumnReference object
   * @throws StandardException
   */
  public ColumnQueryInfo(final ColumnReference cr, QueryInfoContext qic)
      throws StandardException {
    final ResultColumn rc = cr.getSource();
    this.tableNum = cr.getTableNumber();
    this.columnDescriptor = searchColumnDescriptor(rc);

    if (this.columnDescriptor != null) {
      this.actualColumnName = this.columnDescriptor.getColumnName();
      this.actualColumnNumber = this.columnDescriptor.getPosition();
      this.columnType = this.columnDescriptor.getType();
      this.td = this.columnDescriptor.getTableDescriptor();
    }
    else {
      String columnName = rc.getSourceColumnName();
      if (columnName == null) {
        columnName = rc.getActualName();
      }
      this.actualColumnName = columnName;
      this.actualColumnNumber = rc.getColumnPosition();
      this.columnType = rc.getType();
      this.td = null;
    }
    this.exposedColumnName = this.actualColumnName;
    this.virtualColumnNumber = this.actualColumnNumber;
    if (tableNum > -1) {
      DMLQueryInfo current = qic.getCurrentScopeQueryInfo();
      this.tqi = current.getTableQueryInfo(tableNum);
      if (tqi != null) {
        this.initResolverForColumnIfApplicable();
      }
      else {
        DMLQueryInfo root = qic.getRootQueryInfo();
        if (root.isSelect() && qic.getNestingLevelOfScope() > 0) {
          // It is possible that this column refers to an outer scope table.
          // We need to find the Scope to which it belongs
          Scope owningScope = qic.findOwningScope(tableNum);
          if (owningScope != null) {
            this.initResolverForOuterScopeColumn(cr, qic, owningScope);
            this.isOuterScope = true;
          }
          else {
            // the subquery flag remains set as UNSUPPORTED_CORRELATED_SUBQUERY
          }
        }
      }
    }

    // !!!:ezoerner:20090622 a null columnDescriptor is problem if we need the
    // type for byte array formatting. Allow it for now, but may cause an NPE
    // later
    /*
     assert this.columnDescriptor != null;
     */
  }

  /**
   * This constructor is used while generating QueryInfo object for the 
   * table
   * @param cd ColumnDescriptor Object
   * @param tqi Table QueryInfo
   * @param tableNum int
   * @throws StandardException
   */
  ColumnQueryInfo(ColumnDescriptor cd, TableQueryInfo tqi, int tableNum)
      throws StandardException {

    this.actualColumnName = cd.getColumnName();
    this.actualColumnNumber = cd.getPosition();
    this.columnDescriptor = cd;
    this.td = cd.getTableDescriptor();
    this.columnType = cd.getType();
    this.tqi = tqi;
    this.exposedColumnName = this.actualColumnName;
    this.virtualColumnNumber = this.actualColumnNumber;
    assert this.columnDescriptor != null;
  }

  /**
   * This constructor is invoked while analyzing the projection attributes
   * @param rc ResultColumn
   * @throws StandardException
   * @see SelectQueryInfo#visit(com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable)
   */
  ColumnQueryInfo(final ResultColumn rc, QueryInfoContext qic)
      throws StandardException {
    final ColumnDescriptor cd = searchColumnDescriptor(rc);

    if (cd != null) {
      this.actualColumnName = cd.getColumnName();
      this.actualColumnNumber = cd.getPosition();
      this.columnDescriptor = cd;
      this.columnType = cd.getType();
      this.td = cd.getTableDescriptor();
    }
    else {
      String columnName = rc.getSourceColumnName();
      if (columnName == null) {
        columnName = rc.getActualName();
      }
      this.actualColumnName = columnName;
      this.actualColumnNumber = rc.getColumnPosition();
      this.columnType = rc.getType();
      this.td = null;
      this.columnDescriptor = new ColumnDescriptor(this.actualColumnName,
          this.actualColumnNumber, this.columnType,
          null, // default
          null, // defaultInfo
          (UUID)null, // table uuid
          (UUID)null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
    }

    if (tableNum > -1) {
      DMLQueryInfo currentTop = qic.getCurrentScopeQueryInfo();
      this.tqi = currentTop.getTableQueryInfo(tableNum);
      //TODO:Asif Should we do correlation check here? 
    }
    this.exposedColumnName = rc.getName();
    this.virtualColumnNumber = rc.getVirtualColumnId();
    assert this.columnDescriptor != null;
  }

  private final ColumnDescriptor searchColumnDescriptor(final ResultColumn rc)
      throws StandardException {
    ValueNode vn;
    ResultColumn rs = rc;
    ColumnDescriptor cd = rc.getTableColumnDescriptor();
    if (this.tableNum < 0) {
      this.tableNum = rc.getTableNumber();
    }
    if (cd != null) {
      return cd;
    }
    for (;;) {
      if ((vn = rs.getExpression()) instanceof ColumnReference) {
        rs = ((ColumnReference)vn).getSource();
      }
      else if ((vn = rs.getExpression()) instanceof VirtualColumnNode) {
        rs = ((VirtualColumnNode)vn).getSourceColumn();
      }
      else {
        break;
      }
      if (rs != null) {
        cd = rs.getTableColumnDescriptor();
        if (cd != null) {
          this.tableNum = rs.getTableNumber();
          break;
        }
      }
      else {
        break;
      }
    }
    return cd;
  }

  public final int getActualColumnPosition() {
    return this.actualColumnNumber;
  }

  public String getActualColumnName() {
    return this.actualColumnName;
  }

  /**
   * Returns an int indicating the relative positions ( 0 based index) of the PR
   * tables in a query , which decide the index positions in the Colocation
   * Criteria matrix. Previously this used to be done on the basis of Table
   * Number , but because a query may contain replicated tables, the table
   * number may fall outside the Colocation Matrix Criteria array & so adding
   * this API.
   * 
   * @return int indicating a non negative number for PR table & -1 for a
   *         replicated table. This API is added to fix Bug # 40085.
   *
  int getTableNumberForColocationCriteria() {
    return td.getTableNumberForColocationCriteria();
  }*/

  public String getExposedName() {
    return this.exposedColumnName;
  }

  // returns null if this isn't a column
  public ColumnDescriptor getColumnDescriptor() {
    return this.columnDescriptor;
  }
  
  
  public DataTypeDescriptor getType() {
    return this.columnType;
  }

  public int getVirtualPosition() {
    return this.virtualColumnNumber;
  }

  /**
   * Checks if the ColumnQueryInfo of the table matches the 
   * ColumnQueryInfo object of the table description in terms 
   * of exposed name etc based on which the query may be
   * converted into Get  
   * @param resultColumn ColumnQueryInfo object which represents the projection attribute
   * @return boolean true if the proejction attribute column matches the
   * table column for Region.get conversion possibility
   */
  public boolean isResultColumnMatchingTableColumn(ColumnQueryInfo resultColumn) {
    return this.actualColumnNumber == resultColumn.actualColumnNumber
        //&& this.actualColumnName.equals(resultColumn.actualColumnName)
        //&& this.actualColumnNumber == resultColumn.virtualColumnNumber
        && resultColumn.exposedColumnName.equals(this.actualColumnName)
        && this.tqi.getTableNumber() == resultColumn.tqi.getTableNumber();
  }

  public int getTableNumber() {
    return this.tqi.getTableNumber();
  }

  @Override
  public String getTableName() {
    return this.td != null ? this.td.getName() : (this.tqi != null ? this.tqi
        .getTableName() : "");
  }

  @Override
  public String getFullTableName() {
    return this.td != null ? (this.td.getSchemaName() + '.' + this.td.getName())
        : (this.tqi != null ? this.tqi.getFullTableName() : "");
  }

  @Override
  public String getSchemaName() {
    return this.td != null ? this.td.getSchemaName()
        : (this.tqi != null ? this.tqi.getSchemaName() : "");
  }

  @Override
  public AbstractRegion getRegion() {
    return this.tqi != null ? this.tqi.getRegion()
        : (this.td != null ? (AbstractRegion)Misc.getRegionByPath(this.td,
            null, false) : null);
  }

  public TableDescriptor getTableDescriptor() {
    return this.td;
  }

  /**
   * 
   * @return an int indicating the relative fixed position for the PR tables occurring in the 
   * Select Query. It will be zero based index. For replicated tables, the number returned is -1
   */
  int getTableNumberforColocationCriteria() {
    /*
     * TODO need to support sub-query table expression and do it right
     * e.g. select * from (select col1 from tab group by col1) as T
     */
    if(this.tqi == null) return 0;
    return this.isOuterScope ? 0 : this.tqi
        .getTableNumberforColocationCriteria();
  }

  // /////////////////// Methods added as workaround for Bug 39923
  // ////////////////
  //Work around for Bug # 39923 . This method is invoked only through UpdateQueryInfo
  void setMissingTableInfo(TableQueryInfo tqi) {
    this.tqi = tqi;    
    this.initResolverForColumnIfApplicable();
    //this.tableNumber = tqi.getTableNumber();
    //this.td = tqi.getTableDescriptor();    
  }

  boolean isTableInfoMissing() {
    return this.tqi == null;
  }

  @Override
  public String toString() {
    StringBuilder sbuff = new StringBuilder();
    sbuff.append("Full table name = ");
    sbuff.append(getFullTableName());
    sbuff.append("; Actual column name = ");
    sbuff.append(this.actualColumnName);
    sbuff.append("; Exposed Column Name = ");
    sbuff.append(this.exposedColumnName);
    return sbuff.toString();
  }

  boolean isUsedInPartitioning() {
    boolean ok = false;
    //TODO: Asif: Handle this case where region is turning out to be null
    //Ideally Bug 39923 workaround should ensure that region is not null. But we are doing
    //this check only for Update type statements. For Select queries, it may stll be null,
    //hence the check
    
    if( this.tqi == null) {
      return ok;
    }
    Region rgnOwningColumn = this.tqi.getRegion();
    assert rgnOwningColumn != null;
    RegionAttributes ra = rgnOwningColumn.getAttributes();
    // If the region is a Replicated Region or if it is a PR with just
    // itself
    // as a member then we should go with Derby's Activation Object
    DataPolicy policy = ra.getDataPolicy();

    if (policy.withPartitioning()) {
      PartitionedRegion pr = (PartitionedRegion)rgnOwningColumn;
      GfxdPartitionResolver rslvr = GemFireXDUtils.getResolver(pr);
      ok = rslvr != null && rslvr.isUsedInPartitioning(this.actualColumnName);
    }
    return ok;
  }

  boolean isPartOfPrimaryKey(int pkCols[][]) {
    boolean partOfPK= false;    
    if( pkCols != null) {
      for(int i = 0; i<pkCols.length;++i) {
        if(pkCols[i][0] == this.actualColumnNumber) {
          partOfPK = true;
          break;
        }
      }
    }
    return partOfPK;
  }
  
  // TODO:Asif: Should we cache the Conglomerate
  // Descriptor or better still the conglomID? If yes, then if the indxes are
  // dropped etc
  // we will need to take care of it
  List<ConglomerateDescriptor> getAvailableGlobalHashIndexForColumn() {
    if (this.tqi == null) {
      return Collections.emptyList();
    }
    TableDescriptor td = this.tqi.getTableDescriptor();
    assert td != null;
    ConglomerateDescriptorList cdl = td.getConglomerateDescriptorList();
    Iterator itr = cdl.iterator();
    List<ConglomerateDescriptor> globalIndexes = new ArrayList<ConglomerateDescriptor>(
        2);
    while (itr.hasNext()) {
      ConglomerateDescriptor cd = (ConglomerateDescriptor)itr.next();

      if (cd.isIndex() || cd.isConstraint()) {
        IndexDescriptor id = cd.getIndexDescriptor();
        if (id.indexType().equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
          int[] baseColPos = id.baseColumnPositions();
          for (int colPos : baseColPos) {
            if (colPos == this.actualColumnNumber) {
              globalIndexes.add(cd);
              break;
            }
          }

        }
      }
    }
    return globalIndexes;
  }

  int getPartitionColumnPosition() {
    return this.partitioningColPos;
  }

  void isNotNullCriteriaSatisfied(DataValueDescriptor dvd)
      throws StandardException {
    if (!this.columnType.isNullable() && (dvd == null || dvd.isNull())) {
      throw StandardException.newException(SQLState.LANG_NULL_INTO_NON_NULL,
          this.exposedColumnName);
    }
  }

  boolean isReferencedByUniqueConstraint() throws StandardException {
    TableDescriptor td = this.tqi.getTableDescriptor();
    ConstraintDescriptorList cdl = td.getConstraintDescriptorList();
    Iterator<ConstraintDescriptor> itr = cdl.iterator();
    boolean isReferenced = false;
    while (itr.hasNext()) {
      ConstraintDescriptor cd = itr.next();
      if(cd.getConstraintType() == DataDictionary.UNIQUE_CONSTRAINT &&  this.isColumnReferencedByConstraint(cd) != -1 ) {
        isReferenced = true;
        break;
      }           
    }
    return isReferenced;
  }

  /**
   * 
   * @param cd ConstraintDescriptor 
   * @return int indicating the index in the referencedColumns array of the Constraint Descriptor 
   * at which the given column exists. This is better than returning a boolean as it can be used
   * to check if a column being updated is getting repeated & so may not help in fulfilling some criterias
   */
   int isColumnReferencedByConstraint(ConstraintDescriptor cd) {
    int [] referencedColumns = cd.getReferencedColumns();
    int index = -1;
    for (int i =0; i < referencedColumns.length;++i) {
      if (referencedColumns[i] == this.actualColumnNumber) {
        index = i;
        break;
      }
    }    
    return index;
  }
  
  public GfxdPartitionResolver getResolverIfSingleColumnPartition () {
    return (this.partitioningColPos != -1 && 
    this.resolver.getColumnNames().length ==1 && !this.isOuterScope)?
    this.resolver:null;
  }

  private void initResolverForOuterScopeColumn(ColumnReference cr,
      QueryInfoContext qic, Scope owningScope) throws StandardException {
    // we want to find the following:
    // The owning table's Resolver so that we can find the column number as per
    // colocation criteria.
    // That column number will be the same as the root table's colocation column
    // number.

    final int tableNumber = cr.getTableNumber();
    DMLQueryInfo owningDML = owningScope.getDMLQueryInfo();
    this.tqi = owningDML.getTableQueryInfo(tableNumber);    
    this.initResolverForColumnIfApplicable();
    SubQueryInfo sqi = (SubQueryInfo)qic.getCurrentScopeQueryInfo();
    //Now the partitioning column position is correctly set based on the owning region's resolver of outer scope,
    // in case it is a  partitioned table
    //Now we need the resolver of the independent PR based table ( i.e   if this column is part of resolver.)
    // the partitioning column position will remain unchanged.
    if(this.resolver != null) {
      //Check if the owning scope's level is less than independent PR scope level. If yes throw exception 
      
      int independentPRScopeLevel =sqi.getLastPushedPRBasedIndependentScopeLevel(); 
      if(owningScope.getScopeLevel() < independentPRScopeLevel ) {
        throw StandardException .newException(SQLState.COLOCATION_CRITERIA_UNSATISFIED);
      }
      // reset the resolver to the immediate independent query. Note that from this point onwards the resolver will not point to that
      // of owning region but that of immediate independent PR query's resolver. Though rest of the data will point correctly 
      //to owning table
     //This will be the master table of the independent query on which we will base our colocation criteria
      AbstractRegion master =  qic.getScopeAt(independentPRScopeLevel).getDMLQueryInfo().getRegion();
      assert master.getDataPolicy().withPartitioning();      
      this.resolver = GemFireXDUtils.getResolver(master);      
    }
    
  }
  
 
  
  private void initResolverForColumnIfApplicable()
  {
    assert this.tqi != null;
    AbstractRegion rgn = this.tqi.getRegion();
    GfxdPartitionResolver rslvr = GemFireXDUtils.getResolver(rgn);
    if (rslvr != null && rslvr.isUsedInPartitioning(this.actualColumnName)) {
      this.resolver = rslvr;
      this.partitioningColPos = rslvr
          .getPartitioningColumnIndex(this.actualColumnName);

    }
  }

  public boolean isExpression() {
    return false;
  }

  @Override
  public boolean isTableVTI() {
    return columnDescriptor == null || columnDescriptor.getTableDescriptor()
        .getTableType() == TableDescriptor.VTI_TYPE;
  }

  @Override
  public boolean routeQueryToAllNodes() {
    return columnDescriptor == null
        || columnDescriptor.getTableDescriptor().routeQueryToAllNodes();
  }
}
