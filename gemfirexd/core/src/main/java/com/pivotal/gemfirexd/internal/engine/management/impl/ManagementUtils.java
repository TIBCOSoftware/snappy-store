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
package com.pivotal.gemfirexd.internal.engine.management.impl;

import java.text.MessageFormat;

import javax.management.ObjectName;

import com.gemstone.gemfire.management.internal.MBeanJMXAdapter;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.pivotal.gemfirexd.internal.GemFireXDVersion;
import com.pivotal.gemfirexd.internal.engine.management.GfxdMemberMXBean.GfxdMemberMetaData;
import com.pivotal.gemfirexd.internal.engine.management.TableMXBean.TableMetadata;

/**
 * 
 * @author Abhishek Chaudhari
 * @since gfxd 1.0
 */
public abstract class ManagementUtils implements ManagementConstants {
  public static final String DEFAULT_SERVER_GROUP = "DEFAULT";
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  public static final int DEFAULT_MBEAN_UPDATE_RATE_MILLIS = 5000;

  public static final String LINE_SEPARATOR = System.getProperty("line.separator");

  public static final String NA = "NA";
  public static final GfxdMemberMetaData MEMBER_METADATA_NA = new GfxdMemberMetaData(NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA);
  public static final TableMetadata      TABLE_METADATA_NA  = new TableMetadata(NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA, NA);

  public static final String PRODUCT_NAME = GemFireXDVersion.getProductName();


  // ****************** Methods for MBean Object Names start *******************
  private static String makeServerGroupCompliant(String groupName) {
    return makeCompliantName(quoteIfNeeded(groupName.toUpperCase())); // GFXD makes server-group to Upper Case.
  }

  public static ObjectName getMemberMBeanName(String memberNameOrId, String groupName) {
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__GFXDMEMBER_MXBEAN,
            new Object[] { makeServerGroupCompliant(groupName),
                           makeCompliantName(quoteIfNeeded(memberNameOrId)) }));
  }

  public static ObjectName getMemberMBeanNamePattern(String memberNameOrId) {
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__GFXDMEMBER_MXBEAN,
            new Object[] { "*",
                           makeCompliantName(quoteIfNeeded(memberNameOrId)) }));
  }

  public static ObjectName getTableMBeanName(String groupName, String memberNameOrId, String tableName) {
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__TABLE_MXBEAN,
            new Object[] { makeServerGroupCompliant(groupName),
                           makeCompliantName(quoteIfNeeded(memberNameOrId)),
                           makeCompliantName(quoteIfNeeded(tableName)) }));
  }

  public static ObjectName getTableMBeanGroupPattern(String memberNameOrId, String tableName) {
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__TABLE_MXBEAN,
            new Object[] { "*",
                           makeCompliantName(quoteIfNeeded(memberNameOrId)),
                           makeCompliantName(quoteIfNeeded(tableName)) }));
  }

  public static ObjectName getGfxdMBeanPattern() {
    return MBeanJMXAdapter.getObjectName(OBJECTNAME__PREFIX_GFXD + "*");
  }

  public static ObjectName getStatementMBeanName(String memberNameOrId, String executeStatementNameAndSchema) {
    String name= quoteIfNeeded(makeCompliantName(executeStatementNameAndSchema));
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__STATEMENT_MXBEAN,
            new Object[] {makeCompliantName(quoteIfNeeded(memberNameOrId)),
                          name }));
  }

  public static ObjectName getAggregateStatementMBeanName(String name) {
    String compliantName = quoteIfNeeded(makeCompliantName(name));
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__AGGREGATESTATEMENT_MXBEAN,
            new Object[] { compliantName }));
  }

  public static ObjectName getAggrgateTableMBeanName(String fullPath) {
    return MBeanJMXAdapter.getObjectName(
        format(OBJECTNAME__AGGREGATETABLE_MXBEAN,
            new Object[] { fullPath }));
  }

  public static ObjectName getClusterMBeanName() {
    return MBeanJMXAdapter.getObjectName(OBJECTNAME__AGGREGATEMEMBER_MXBEAN);
  }

  private static String makeCompliantName(String value) {
    return MBeanJMXAdapter.makeCompliantName(value);
  }

  private static String quoteIfNeeded(String stringToCheck) {
    return (stringToCheck != null && (stringToCheck.indexOf('"') != -1) ) ? ObjectName.quote(stringToCheck) : stringToCheck;
  }

  /**
   * Creates a MessageFormat with the given pattern and uses it to format the
   * given argument.
   *
   * @param pattern
   *          the pattern to be substituted with given argument
   * @param argument
   *          an object to be formatted and substituted
   * @return formatted string
   * @throws IllegalArgumentException
   *           if the pattern is invalid, or the argument is not of the type
   *           expected by the format element(s) that use it.
   */
  public static String format(String pattern, Object argument) {
    return format(pattern, new Object[] { argument });
  }

  /**
   * Creates a MessageFormat with the given pattern and uses it to format the
   * given arguments.
   *
   * @param pattern
   *          the pattern to be substituted with given arguments
   * @param arguments
   *          an array of objects to be formatted and substituted
   * @return formatted string
   * @throws IllegalArgumentException
   *           if the pattern is invalid, or if an argument in the
   *           <code>arguments</code> array is not of the type expected by the
   *           format element(s) that use it.
   */
  public static String format(String pattern, Object... arguments) {
    return MessageFormat.format(pattern, arguments);
  }

  // ***************** Methods for MBean Object Names end **********************
}
