/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.ui;

import java.util.Locale;

public class SnappyUtils {

  public static String STORAGE_SIZE_UNIT_ANY = "ANY";
  public static String STORAGE_SIZE_UNIT_B   = "B";
  public static String STORAGE_SIZE_UNIT_KB  = "KB";
  public static String STORAGE_SIZE_UNIT_MB  = "MB";
  public static String STORAGE_SIZE_UNIT_GB  = "GB";
  public static String STORAGE_SIZE_UNIT_TB  = "TB";
  public static String STORAGE_SIZE_UNIT_PB  = "PB";

  /**
   * Convert a quantity in bytes to a human-readable string in specified units.
   */
  public static double bytesToGivenUnits(long size, String expectedUnit) {
    long PB = 1L << 50;
    long TB = 1L << 40;
    long GB = 1L << 30;
    long MB = 1L << 20;
    long KB = 1L << 10;

    double value;

    if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_PB)) {
      value = (double) size / PB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_TB)) {
      value = (double) size / TB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_GB)) {
      value = (double) size / GB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_MB)) {
      value = (double) size / MB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_KB)) {
      value = (double) size / KB;
    } else {
      value = (double) size;
    }

    return value;
  }

  /**
   * Convert a quantity in bytes to a human-readable string such as "4.0 MB".
   * Optionally, units for conversion can be specified.
   */
  public static String bytesToString(long size,  String expectedUnit ) {
    long PB = 1L << 50;
    long TB = 1L << 40;
    long GB = 1L << 30;
    long MB = 1L << 20;
    long KB = 1L << 10;

    double value;
    String unit;

    if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_PB) ||
        (size >= 2*PB && expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_ANY))) {
      value = (double) size / PB;
      unit = SnappyUtils.STORAGE_SIZE_UNIT_PB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_TB) ||
        (size >= 2*TB && expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_ANY))) {
      value = (double) size / TB;
      unit = SnappyUtils.STORAGE_SIZE_UNIT_TB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_GB) ||
        (size >= 2*GB && expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_ANY))) {
      value = (double) size / GB;
      unit = SnappyUtils.STORAGE_SIZE_UNIT_GB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_MB) ||
        (size >= 2*MB && expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_ANY))) {
      value = (double) size / MB;
      unit = SnappyUtils.STORAGE_SIZE_UNIT_MB;
    } else if (expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_KB) ||
        (size >= 2*KB && expectedUnit.equalsIgnoreCase(SnappyUtils.STORAGE_SIZE_UNIT_ANY))) {
      value = (double) size / KB;
      unit = SnappyUtils.STORAGE_SIZE_UNIT_KB;
    } else {
      value = (double) size;
      unit = SnappyUtils.STORAGE_SIZE_UNIT_B;
    }

    return String.format(Locale.getDefault(), "%.1f %s", value, unit);
  }

}
