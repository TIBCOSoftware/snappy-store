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
package com.pivotal.gemfirexd;

/**
 * List of GemFireXD constants that is used other than {@link Attribute} and
 * {@link Property}.
 * 
 * @author soubhikc
 */
// if renamed, messages.xml should also be adjusted accordingly.
public interface Constants {

  /**
   * Possible values of GEMFIREXD-PROPERTIES property keys.
   * <p>
   * Keys are case-sensitive and enumeration.name() method should be used for
   * appropriate key strings.
   * 
   * @author soubhikc
   */
  // if renamed, messages.xml should also be adjusted accordingly.
  public static enum QueryHints {

    /**
     * To override default optimizer's choice of join strategy.
     * <p>
     * Possible values NESTEDLOOP or HASH
     * 
     * <p>
     * This can be FROM clause as well as table optimizer hint, in other words, this can be
     * mentioned between FROM clause and the first table name or after each table name.
     * 
     * <p>
     * examples: <br>
     * <code> SELECT a, b FROM -- GEMFIREXD-PROPERTIES joinStrategy=HASH \n userTable1
     * t1, userTable2 t2 WHERE t1.col1 = t2.col1
     * </code> <br><br>
     * <code> SELECT a, b FROM userTable1
     * t1 -- GEMFIREXD-PROPERTIES joinStrategy=HASH \n, userTable2 t2 WHERE t1.col1 = t2.col1
     * </code>
     */
    joinStrategy,

    /**
     * Query Hint to override optimizer's choice of ordering tables. Typically
     * used in conjunction with {@link #joinStrategy}.
     * 
     * <p>
     * Possible values are FIXED or UNFIXED, where FIXED will ensure ordering of
     * the tables as appeared in the FROM list.
     * 
     * <p>
     * This can only be used with FROM clause, in other words, this can only be
     * mentioned between FROM clause and the first table name.
     * 
     * <p>
     * example: <br>
     * <code> SELECT a, b FROM -- GEMFIREXD-PROPERTIES joinOrder=FIXED \n userTable1
     * t1, userTable2 t2 -- GEMFIREXD-PROPERTIES joinStrategy=HASH \n WHERE t1.col1 = t2.col1
     * </code>
     */
    joinOrder,

    /**
     * Query Hint to force usage of any particular index.
     * 
     * <p>
     * Possible values are unqualified index name or <code>NULL</code>
     * indicating table scan.
     * 
     * <p>
     * This can only be used within TableExpression and only on base tables.
     * Cannot be specified for views or derived tables.
     */
    index,

    /**
     * Query Hint to force usage of index enforcing a particular constraint.
     * This is similar to {@link #index} property but index is used via
     * constraint name.
     * 
     * <p>
     * Possible values are unqualified constraint name of primary key, foreign
     * key or unique constraint.
     * 
     * <p>
     * This can only be used within TableExpression and only on base tables.
     */
    constraint,

    hashInitialCapacity,

    hashLoadFactor,

    hashMaxCapacity,

    bulkFetch,

    /**
     * Query Hint to indicate inclusion of secondary buckets of partitioned
     * tables while joining with virtual tables.
     * 
     * <p>
     * If <code>true</code>, partitioned table's secondary bucket will be
     * considered for routing and scanning while performing the join with
     * virtual table output.
     * 
     * <p>
     * This can only be used on base tables or virtual tables.
     * 
     * <p>
     * example: <br>
     * <code> SELECT dsid(), count(1) from sys.members , userPartitionedTable t1 
     * -- GEMFIREXD-PROPERTIES withSecondaries=true \n GROUP BY dsid()
     * </code>
     * 
     * Following query will return Entry Size/Value Size for primary buckets
     * only. <code>
     * SELECT * FROM sys.memoryAnalytics -- GEMFIREXD-PROPERTIES withSecondaries=false \n
     * </code>
     */
    withSecondaries,

    /**
     * Query Hint to alias an user query that will be shown in Visual Statistics
     * Display (VSD) instead of system generated statement alias.
     * 
     * <p>
     * This can only be used in FROM clause i.e. immediately after the FROM
     * clause and before the table name. WhiteSpace characters are not allowed
     * as word separator.
     * 
     * <p>
     * example: <br>
     * <code>
     * SELECT * FROM -- GEMFIREXD-PROPERTIES statementAlias=My_QUERY \n userTable t1 
     * -- GEMFIREXD-PROPERTIES index=IDX_COL1 \n WHERE t1.col1 is not null
     * </code>
     */
    statementAlias,
    
    /**
     * Query hint to specify whether to query HDFS data.
     * 
     * <p>
     * Possible values are 'true' or 'false'.
     * 
     * <p>
     * This can only be used as a table optimizer hint i.e. can only be specified
     * immediately after each table name.
     * 
     * <p>
     * example: <br>
     * SELECT * FROM userTable t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n  
     *  WHERE t1.col1 is not null
     */
    queryHDFS,    

    /**
     * Memory Analytics sizer query hints.
     * 
     * @see SizerHints
     */
    sizerHints;

    /**
     * SizerHint constant values which can be passed comma separated.
     * <p>
     * example:<br>
     * <code>
     *   SELECT * FROM sys.memoryAnalytics -- GEMFIREXD-PROPERTIES sizerHints=withMemoryFootPrint \n
     * </code>
     * <br><br>
     * jdbc example:<br>
     * <blockquote><pre>
     * <code>
     *   java.sql.Statement st = conn.createStatement();
     *   StringBuilder sb = new StringBuilder("SELECT * FROM sys.memoryAnalytics -- GEMFIREXD-PROPERTIES sizerHints=");
     *   sb.append(com.pivotal.gemfirexd.Constants.QueryHints.SizerHints.withMemoryFootPrint.name());
     *   sb.append("\n"); 
     *   java.sql.ResultSet rs = st.executeQuery(sb.toString());
     *   while(rs.next()) { 
     *     System.out.println(rs.getObject(1));
     *   } <br>
     * </code>
     * </pre></blockquote>
     * @author soubhikc
     */
    // if renamed, messages.xml should also be adjusted accordingly.
    // TODO add other parameters , logObjectReferenceGraph, logTopConsumers, traceOutput, traceVerbose
    public static enum SizerHints {
      withMemoryFootPrint,
      logObjectReferenceGraph,
      logTopConsumers,
      traceOutput,
      traceVerbose
    }
  }

  public static final String LDAP_LOCAL_USER_DN = "gemfirexd.user";

  public static final String LDAP_SEARCH_FILTER_USERNAME = "%USERNAME%";

  /**
   * Prefix used for user names internally when granting privileges to those
   * coming from LDAP groups.
   */
  public static final String LDAP_GROUP_PREFIX = "LDAPGROUP:";

  /**
   * The token for the LDAP group name provided in custom LDAP group search
   * filter specification.
   */
  public static final String LDAP_SEARCH_FILTER_GROUP = "%GROUP%";

  /**
   * Property value to AUTH_PROVIDER or to SERVER_AUTH_PROVIDER for enabling
   * BUILTIN scheme.
   * 
   */
  String AUTHENTICATION_PROVIDER_BUILTIN = "BUILTIN";

  /**
   * Property value to AUTH_PROVIDER or to SERVER_AUTH_PROVIDER for enabling
   * LDAP scheme.<BR>
   * <BR>
   * 
   * Verifying LDAP server can be configured by setting
   * {@link com.pivotal.gemfirexd.Property#AUTH_LDAP_SERVER ldapserver} property.
   * 
   */
  String AUTHENTICATION_PROVIDER_LDAP = "LDAP";

  /**
   * The security mechanism to use when starting network server to impose policy
   * for client connections.
   * <p>
   * Security mechanism options are:
   * <ul>
   * <li>USER_ONLY_SECURITY
   * <li>CLEAR_TEXT_PASSWORD_SECURITY
   * <li>ENCRYPTED_PASSWORD_SECURITY
   * <li>ENCRYPTED_USER_AND_PASSWORD_SECURITY
   * <li>STRONG_PASSWORD_SUBSTITUTE_SECURITY
   * </ul>
   * The default security mechanism is USER_ONLY SECURITY
   * <p>
   * If the application specifies a security mechanism then it will be the only
   * one attempted. If the specified security mechanism is not supported by the
   * conversation then an exception will be thrown and there will be no
   * additional retries.
   * <p>
   * Both user and password need to be set for all security mechanism except
   * USER_ONLY_SECURITY
   */
  public static enum SecurityMechanism {
    /**
     * SECMEC_USRIDONL = 4
     * <p>
     * Only user id is required.
     */
    USER_ONLY_SECURITY,
    /**
     * SECMEC_USRIDPWD = 3
     * <p>
     * Both user id and password are required. password is in clear text.
     */
    CLEAR_TEXT_PASSWORD_SECURITY,
    /**
     * SECMEC_EUSRIDPWD = 9
     * <p>
     * both password and user are encrypted
     */
    ENCRYPTED_USER_AND_PASSWORD_SECURITY,
    /**
     * SECMEC_USRSSBPWD = 8
     * <p>
     * Password substitute is to be used.
     */
    STRONG_PASSWORD_SUBSTITUTE_SECURITY;
  };
}
