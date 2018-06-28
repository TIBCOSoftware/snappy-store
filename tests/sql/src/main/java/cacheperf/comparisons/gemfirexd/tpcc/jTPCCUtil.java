/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
/*
 * jTPCCUtil - utility functions for the Open Source Java implementation of
 *    the TPC-C benchmark
 *
 * Copyright (C) 2003, Raul Barbosa
 * Copyright (C) 2004-2006, Denis Lussier
 *
 */
package cacheperf.comparisons.gemfirexd.tpcc;

import java.util.Random;

import hydra.GsRandom;

public class jTPCCUtil {

  private static final String[] nameTokens = {
    "BAR", "OUGHT", "ABLE",  "PRI",   "PRES",
    "ESE", "ANTI",  "CALLY", "ATION", "EING"
  };

  private static int itemBase = TPCCPrms.getItemBase();
  private static int customerBase = TPCCPrms.getCustomerBase();

  static final ThreadLocal<Random> rnd = new ThreadLocal<Random>() {
    @Override
    protected Random initialValue() {
      return new Random();
    }
  };

  static final char[] chooseChars =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
          .toCharArray();

  public static String randomStr(long strLen) {
    if (strLen > 1) {
      strLen--;
      final int nChars = chooseChars.length;
      char[] freshString = new char[(int)strLen];
      Random r = rnd.get();
      for (int i = 0; i < strLen; i++) {
        freshString[i] = chooseChars[r.nextInt(nChars)];
      }
      return new String(freshString);
    }
    else {
      return "";
    }
    /*
    String freshString = "";

    while (freshString.length() < (strLen - 1)) {
      freshChar= (char)(Math.random()*128);
      if (Character.isLetter(freshChar)) {
        freshString += freshChar;
      }
    }
    return freshString;
    */
  }

  public static int getItemID(GsRandom r, int numItems) {
    return nonUniformRandom(itemBase, 1, numItems, r);
  }

  public static int getCustomerID(GsRandom r, int customersPerDistrict) {
    return nonUniformRandom(customerBase, 1, customersPerDistrict, r);
  }

  public static String getLastName(GsRandom r) {
    int num = (int)nonUniformRandom(255, 0, 999, r);
    return nameTokens[num/100] + nameTokens[(num/10)%10] + nameTokens[num%10];
  }

  public static int randomNumber(int min, int max, GsRandom r) {
    return (int)(r.nextDouble() * (max-min+1) + min);
  }

  public static int nonUniformRandom(int x, int min, int max, GsRandom r) {
    return (((randomNumber(0, x, r) | randomNumber(min, max, r))
            + randomNumber(0, x, r)) % (max-min+1)) + min;
  }

  public static void shuffle(int[] cards, GsRandom r) {
    for (int i = 0; i < cards.length; i++) {
      int j = r.nextInt(0, cards.length - 1);
      int tmp = cards[i];
      cards[i] = cards[j];
      cards[j] = tmp;
    }
  }
}
