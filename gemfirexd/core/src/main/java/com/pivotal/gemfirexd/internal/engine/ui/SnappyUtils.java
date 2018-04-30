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

  public enum StorageSizeUnits {
    B, KB, MB, GB, TB, ANY
  }

  /**
   * Convert a quantity in bytes to a human-readable string in specified units.
   */
  public static double bytesToGivenUnits(long size, StorageSizeUnits expectedUnit) {
    long TB = 1L << 40;
    long GB = 1L << 30;
    long MB = 1L << 20;
    long KB = 1L << 10;

    double value;

    if (expectedUnit == StorageSizeUnits.TB) {
      value = (double) size / TB;
    } else if (expectedUnit == StorageSizeUnits.GB) {
      value = (double) size / GB;
    } else if (expectedUnit == StorageSizeUnits.MB) {
      System.out.println("in MB");
      value = (double) size / MB;
    } else if (expectedUnit == StorageSizeUnits.KB) {
      System.out.println("in KB");
      value = (double) size / KB;
    } else {
      System.out.println("in ELSE");
      value = (double) size;
    }

    return value;
  }

  /**
   * Convert a quantity in bytes to a human-readable string such as "4.0 MB".
   * Optionally, units for conversion can be specified.
   */
  public static String bytesToString(long size,  StorageSizeUnits expectedUnit ) {
    long TB = 1L << 40;
    long GB = 1L << 30;
    long MB = 1L << 20;
    long KB = 1L << 10;

    double value;
    String unit;

    if (expectedUnit == StorageSizeUnits.TB ||
        (size >= 2*TB && expectedUnit == StorageSizeUnits.ANY)) {
      value = (double) size / TB;
      unit = "TB";
    } else if (expectedUnit == StorageSizeUnits.GB ||
        (size >= 2*GB && expectedUnit == StorageSizeUnits.ANY)) {
      value = (double) size / GB;
      unit = "GB";
    } else if (expectedUnit == StorageSizeUnits.MB ||
        (size >= 2*MB && expectedUnit == StorageSizeUnits.ANY)) {
      value = (double) size / MB;
      unit = "MB";
    } else if (expectedUnit == StorageSizeUnits.KB ||
        (size >= 2*KB && expectedUnit == StorageSizeUnits.ANY)) {
      value = (double) size / KB;
      unit = "KB";
    } else {
      value = (double) size;
      unit = "B";
    }

    return String.format(Locale.US, "%.1f %s", value, unit);
  }

}
