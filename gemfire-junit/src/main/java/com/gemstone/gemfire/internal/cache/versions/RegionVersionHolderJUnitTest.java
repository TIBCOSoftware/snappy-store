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
package com.gemstone.gemfire.internal.cache.versions;

import java.io.FileReader;
import java.io.Reader;
import java.net.InetAddress;
import java.util.BitSet;

import junit.framework.TestCase;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.versions.RVVException.ReceivedVersionsIterator;

public class RegionVersionHolderJUnitTest extends TestCase {
  
  protected InternalDistributedMember member;
  
  int originalBitSetWidth = RegionVersionHolder.BIT_SET_WIDTH;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    member = new InternalDistributedMember(InetAddress.getLocalHost(), 12345);
  }



  public void testInitialized() {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    vh1.recordVersion(56, null);
    vh1.recordVersion(57, null);
    vh1.recordVersion(58, null);
    vh1.recordVersion(59, null);
    vh1.recordVersion(60, null);
    vh1 = vh1.clone();
    System.out.println("This node init, vh1="+vh1);

    RegionVersionHolder vh2 = new RegionVersionHolder(member);
    for(int i=1;i<57;i++) {
      vh2.recordVersion(i,null);
    }
    vh2 = vh2.clone();
    System.out.println("GII node init, vh2="+vh2);

    vh1.initializeFrom(vh2);
    vh1 = vh1.clone();
    System.out.println("After initialize, vh1="+vh1);

    //vh1.recordVersion(57,null);
    vh1.recordVersion(58,null);
    vh1.recordVersion(59,null);
    vh1.recordVersion(60,null);

    System.out.println("After initialize and record version, vh1="+vh1);

    vh1 = vh1.clone();
    vh1.recordVersion(57,null);
    System.out.println("After initialize and record version after clone, vh1="+vh1);

    assertTrue(vh1.contains(58));
  }


  public void test1() {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    vh1.recordVersion(15, null);
    RegionVersionHolder vh2 = vh1.clone();

    RegionVersionHolder vh3 = new RegionVersionHolder(member);
    for (int i=1; i<=10; i++) {
      vh3.recordVersion(i, null);
    }

    vh2.initializeFrom(vh3);
    System.out.println("after init, vh2="+vh2);
    System.out.println("after init, vh2="+vh2.clone());

    //assertTrue(vh2.contains(11));// FAILS
    assertTrue(vh2.contains(10));
    assertTrue(vh2.contains(9));

    RegionVersionHolder vh4 = vh2.clone();
    vh2.recordVersion(12, null);
    vh4.recordVersion(12, null);

    vh2.recordVersion(14, null);
    vh4.recordVersion(14, null);

    System.out.println("after init, vh2="+vh2);
    System.out.println("after init, vh2="+vh2.clone());
    System.out.println("after init, vh4="+vh4);
    System.out.println("after init, vh4="+vh4.clone());

    vh4 = vh2.clone();
    vh2.recordVersion(18, null);
    vh4.recordVersion(18, null);
    //vh2.recordVersion(11, null);
    //assertTrue(vh2.contains(11));// FAILS
    System.out.println("after init, vh2="+vh2);
    System.out.println("after init, vh2="+vh2.clone());
    System.out.println("after init, vh4="+vh4);
    System.out.println("after init, vh4="+vh4.clone());
  }

  public void testInitialize3() {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);

    for(int i=0;i<=1074; i++) {
      vh1.recordVersion(i, null);
    }

    RegionVersionHolder vh2 = new RegionVersionHolder(member);
    vh2.recordVersion(1075,null);

    vh1.makeReadyForRecording();
    vh2.initializeFrom(vh1.clone());
    vh2.makeReadyForRecording();
    vh2.recordVersion(1075,null);

    System.out.println("vh1" + vh2.clone());

//    vh2.recordVersion(1098, null);
//
//    System.out.println("vh1" + vh2.clone());
//    System.out.println(vh2.contains(1097));
  }


  public void testInitialize2() {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    vh1.recordVersion(1093, null);
    vh1.recordVersion(2000, null);
    System.out.println("vh1" + vh1);


    RegionVersionHolder vh2 = vh1.clone();
    System.out.println("vh2" + vh1.clone());

    RegionVersionHolder vh3 = new RegionVersionHolder(member);
    for (int i = 1; i <= 1094; i++) {
      vh3.recordVersion(i, null);
    }
    System.out.println("vh3=" + vh3.clone());

    vh2.initializeFrom(vh3);
    System.out.println("after init, vh2=" + vh2.clone() + vh2.contains(2000));

    vh2.recordVersion(1098, null);
    //vh2.recordVersion(1096, null);

    System.out.println("after init, vh2=" + vh2 + " " + vh2.clone() +  vh2.contains(1095) + " " + vh2.contains(1096));

    vh2 = vh2.clone();
    vh2.recordVersion(1096, null);

    System.out.println("after init, vh2=" + vh2 + " " + vh2.clone());


    vh2.recordVersion(1097, null);

    System.out.println("after init, vh2=" + vh2.clone());

    System.out.println(vh2.contains(1096));
    System.out.println(vh2.contains(1097));

    vh2.recordVersion(1095, null);

    System.out.println("after init, vh2=" + vh2.clone());

    System.out.println(vh2.contains(1096));
    /*//assertTrue(vh2.contains(11));// FAILS
    assertTrue(vh2.contains(10));
    assertTrue(vh2.contains(9));

    vh2.recordVersion(11, null);
    assertTrue(vh2.contains(11));// FAILS*/

  }

  public void testInitialize() {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    vh1.recordVersion(11, null);
    RegionVersionHolder vh2 = vh1.clone();

    RegionVersionHolder vh3 = new RegionVersionHolder(member);
    for (int i=1; i<=10; i++) {
      vh3.recordVersion(i, null);
    }

    vh2.initializeFrom(vh3);
    System.out.println("after init, vh2="+vh2);
    //assertTrue(vh2.contains(11));// FAILS
    assertTrue(vh2.contains(10));
    assertTrue(vh2.contains(9));

    vh2.recordVersion(11, null);
    assertTrue(vh2.contains(11));// FAILS

/*    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    vh1.recordVersion(11, null);
    System.out.println("vh1="+vh1);

    RegionVersionHolder vh2 = vh1.clone();
    System.out.println("after clone, vh2="+vh2);

    {
      RegionVersionHolder vh3 = new RegionVersionHolder(member);
      for (int i=1; i<=10; i++) {
        vh3.recordVersion(i, null);
      }

      // create special exception 10(3-11), bitsetVerson=3
      vh2.initializeFrom(vh3);
      System.out.println("after init, vh2="+vh2);
      assertTrue(vh2.contains(10));
      assertTrue(vh2.contains(9));
      vh2.recordVersion(11, null);
      assertTrue(vh2.contains(11));*/
      /*assertEquals(10, vh2.getVersion());


      vh3.recordVersion(8, null);
      System.out.println(vh3.contains(8));
      // to make bsv3,bs=[0,1]
      vh3.recordVersion(4, null);
      System.out.println("after record 4, vh3="+vh3);
      assertEquals(4, vh3.getVersion());

      vh3.recordVersion(7, null);
      System.out.println("after record 7, vh3="+vh3);
      assertEquals(7, vh3.getVersion());
      System.out.println(vh3.contains(7));*/
   // }
  }

  public void test48066_1() {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    for (int i=1; i<=3; i++) {
      vh1.recordVersion(i, null);
    }
    System.out.println("vh1="+vh1);
    
    RegionVersionHolder vh2 = vh1.clone();
    System.out.println("after clone, vh2="+vh2);
    
    {
      RegionVersionHolder vh3 = new RegionVersionHolder(member);
      for (int i=10; i<=10; i++) {
        vh3.recordVersion(i, null);
      }
      
      // create special exception 10(3-11), bitsetVerson=3
      vh3.initializeFrom(vh2);
      System.out.println("after init, vh3="+vh3);
      assertEquals(3, vh3.getVersion());

      vh3.recordVersion(8, null);
      System.out.println(vh3.contains(8));
      // to make bsv3,bs=[0,1]
      vh3.recordVersion(4, null);
      System.out.println("after record 4, vh3="+vh3);
      assertEquals(8, vh3.getVersion());

      vh3.recordVersion(7, null);
      System.out.println("after record 7, vh3="+vh3);
      assertEquals(8, vh3.getVersion());
      System.out.println(vh3.contains(7));
    }
  }
  
  public void test48066() {
    RegionVersionHolder vh = new RegionVersionHolder(member);
    BitSet bs = new BitSet();
    bs.set(0, 8679);
    bs.set(8705, 8713);
    recordVersions(vh, bs);
    System.out.println("init:\t\t" +vh);
    //This is what happens when we apply an RVV from a member that doesn't
    //have an entry for a member that we do have an entry from
    vh.initializeFrom(new RegionVersionHolder(0));
    System.out.println("init from:\t" +vh);
    vh.recordVersion(1,null);
    vh.recordVersion(2,null);
    vh.recordVersion(14,null);
    vh.recordVersion(62,null);
    vh.recordVersion(95,null);
    vh.recordVersion(96,null);
    vh.recordVersion(97,null);
    vh.recordVersion(98,null);
    vh.recordVersion(99,null);
    vh.recordVersion(100,null);
    vh.recordVersion(123,null);
    vh.recordVersion(144,null);
    vh.recordVersion(146,null);
    vh.recordVersion(147,null);
    vh.recordVersion(148,null);
    vh.recordVersion(149,null);
    vh.recordVersion(150,null);
    vh.recordVersion(151,null);
    vh.recordVersion(152,null);
    vh.recordVersion(153,null);
    vh.recordVersion(154,null);
    vh.recordVersion(155,null);
    vh.recordVersion(156,null);
    vh.recordVersion(157,null);
    vh.recordVersion(158,null);
    vh.recordVersion(159,null);
    vh.recordVersion(160,null);
    vh.recordVersion(161,null);
    vh.recordVersion(184,null);
    vh.recordVersion(185,null);
    vh.recordVersion(186,null);
    vh.recordVersion(187,null);
    vh.recordVersion(188,null);
    vh.recordVersion(189,null);
    vh.recordVersion(190,null);
    vh.recordVersion(191,null);
    vh.recordVersion(192,null);
    vh.recordVersion(193,null);
    vh.recordVersion(194,null);
    vh.recordVersion(195,null);
    vh.recordVersion(197,null);
    vh.recordVersion(196,null);
    vh.recordVersion(201,null);
    vh.recordVersion(202,null);
    vh.recordVersion(203,null);
    vh.recordVersion(204,null);
    vh.recordVersion(205,null);
    vh.recordVersion(206,null);
    vh.recordVersion(207,null);
    vh.recordVersion(208,null);
    vh.recordVersion(209,null);
    vh.recordVersion(210,null);
    vh.recordVersion(211,null);
    vh.recordVersion(212,null);
    vh.recordVersion(213,null);
    vh.recordVersion(214,null);
    vh.recordVersion(215,null);
    vh.recordVersion(216,null);
    vh.recordVersion(217,null);
    vh.recordVersion(218,null);
    vh.recordVersion(219,null);
    vh.recordVersion(220,null);
    vh.recordVersion(221,null);
    vh.recordVersion(222,null);
    vh.recordVersion(224,null);
    vh.recordVersion(223,null);
    vh.recordVersion(238,null);
    vh.recordVersion(261,null);
    vh.recordVersion(262,null);
    vh.recordVersion(263,null);
    vh.recordVersion(264,null);
    vh.recordVersion(265,null);
    vh.recordVersion(266,null);
    vh.recordVersion(267,null);
    vh.recordVersion(268,null);
    vh.recordVersion(269,null);
    vh.recordVersion(270,null);
    vh.recordVersion(271,null);
    vh.recordVersion(272,null);
    vh.recordVersion(285,null);
    vh.recordVersion(286,null);
    vh.recordVersion(308,null);
    vh.recordVersion(309,null);
    vh.recordVersion(311,null);
    vh.recordVersion(312,null);
    vh.recordVersion(360,null);
    vh.recordVersion(361,null);
    vh.recordVersion(362,null);
    vh.recordVersion(363,null);
    vh.recordVersion(364,null);
    vh.recordVersion(365,null);
    vh.recordVersion(390,null);
    vh.recordVersion(391,null);
    vh.recordVersion(392,null);
    vh.recordVersion(393,null);
    vh.recordVersion(394,null);
    vh.recordVersion(395,null);
    vh.recordVersion(396,null);
    vh.recordVersion(397,null);
    vh.recordVersion(399,null);
    vh.recordVersion(398,null);
    vh.recordVersion(400,null);
    vh.recordVersion(401,null);
    vh.recordVersion(402,null);
    vh.recordVersion(409,null);
    vh.recordVersion(410,null);
    vh.recordVersion(412,null);
    vh.recordVersion(413,null);
    vh.recordVersion(417,null);
    vh.recordVersion(418,null);
    vh.recordVersion(425,null);
    vh.recordVersion(427,null);
    vh.recordVersion(426,null);
    vh.recordVersion(428,null);
    vh.recordVersion(429,null);
    vh.recordVersion(431,null);
    vh.recordVersion(430,null);
    vh.recordVersion(432,null);
    vh.recordVersion(433,null);
    vh.recordVersion(448,null);
    vh.recordVersion(449,null);
    vh.recordVersion(456,null);
    vh.recordVersion(457,null);
    vh.recordVersion(483,null);
    vh.recordVersion(484,null);
    vh.recordVersion(485,null);
    vh.recordVersion(490,null);
    vh.recordVersion(491,null);
    vh.recordVersion(492,null);
    vh.recordVersion(515,null);
    vh.recordVersion(516,null);
    vh.recordVersion(517,null);
    vh.recordVersion(518,null);
    vh.recordVersion(546,null);
    vh.recordVersion(548,null);
    vh.recordVersion(555,null);
    vh.recordVersion(556,null);
    vh.recordVersion(557,null);
    vh.recordVersion(574,null);
    vh.recordVersion(575,null);
    vh.recordVersion(577,null);
    vh.recordVersion(576,null);
    vh.recordVersion(578,null);
    vh.recordVersion(583,null);
    vh.recordVersion(584,null);
    vh.recordVersion(585,null);
    vh.recordVersion(586,null);
    vh.recordVersion(587,null);
    vh.recordVersion(613,null);
    vh.recordVersion(632,null);
    vh.recordVersion(656,null);
    vh.recordVersion(657,null);
    vh.recordVersion(658,null);
    vh.recordVersion(659,null);
    vh.recordVersion(660,null);
    vh.recordVersion(661,null);
    vh.recordVersion(662,null);
    vh.recordVersion(684,null);
    vh.recordVersion(697,null);
    vh.recordVersion(698,null);
    vh.recordVersion(699,null);
    vh.recordVersion(700,null);
    vh.recordVersion(701,null);
    vh.recordVersion(717,null);
    vh.recordVersion(718,null);
    vh.recordVersion(722,null);
    vh.recordVersion(723,null);
    vh.recordVersion(741,null);
    vh.recordVersion(742,null);
    vh.recordVersion(743,null);
    vh.recordVersion(762,null);
    vh.recordVersion(782,null);
    vh.recordVersion(783,null);
    vh.recordVersion(784,null);
    vh.recordVersion(785,null);
    vh.recordVersion(802,null);
    vh.recordVersion(803,null);
    vh.recordVersion(816,null);
    vh.recordVersion(837,null);
    vh.recordVersion(857,null);
    vh.recordVersion(860,null);
    vh.recordVersion(877,null);
    vh.recordVersion(878,null);
    vh.recordVersion(879,null);
    vh.recordVersion(881,null);
    vh.recordVersion(880,null);
    vh.recordVersion(882,null);
    vh.recordVersion(883,null);
    vh.recordVersion(902,null);
    vh.recordVersion(903,null);
    vh.recordVersion(904,null);
    vh.recordVersion(929,null);
    vh.recordVersion(940,null);
    vh.recordVersion(941,null);
    vh.recordVersion(966,null);
    vh.recordVersion(967,null);
    vh.recordVersion(968,null);
    vh.recordVersion(979,null);
    vh.recordVersion(980,null);
    vh.recordVersion(981,null);
    vh.recordVersion(982,null);
    vh.recordVersion(983,null);
    vh.recordVersion(1083,null);
    vh.recordVersion(1107,null);
    vh.recordVersion(1108,null);
    vh.recordVersion(1130,null);
    vh.recordVersion(1147,null);
    vh.recordVersion(1148,null);
    vh.recordVersion(1149,null);
    vh.recordVersion(1150,null);
    vh.recordVersion(1151,null);
    vh.recordVersion(1152,null);
    vh.recordVersion(1156,null);
    vh.recordVersion(1157,null);
    vh.recordVersion(1158,null);
    vh.recordVersion(1167,null);
    vh.recordVersion(1185,null);
    vh.recordVersion(1186,null);
    vh.recordVersion(1187,null);
    vh.recordVersion(1188,null);
    vh.recordVersion(1189,null);
    vh.recordVersion(1190,null);
    vh.recordVersion(1191,null);
    vh.recordVersion(1200,null);
    vh.recordVersion(1201,null);
    vh.recordVersion(1202,null);
    vh.recordVersion(1222,null);
    vh.recordVersion(1258,null);
    vh.recordVersion(1259,null);
    vh.recordVersion(1260,null);
    vh.recordVersion(1261,null);
    vh.recordVersion(1262,null);
    vh.recordVersion(1263,null);
    vh.recordVersion(1289,null);
    vh.recordVersion(1292,null);
    vh.recordVersion(1293,null);
    vh.recordVersion(1294,null);
    vh.recordVersion(1322,null);
    vh.recordVersion(1323,null);
    vh.recordVersion(1324,null);
    vh.recordVersion(1325,null);
    vh.recordVersion(1327,null);
    vh.recordVersion(1326,null);
    vh.recordVersion(1328,null);
    vh.recordVersion(1329,null);
    vh.recordVersion(1330,null);
    vh.recordVersion(1351,null);
    vh.recordVersion(1352,null);
    vh.recordVersion(1353,null);
    vh.recordVersion(1354,null);
    vh.recordVersion(1418,null);
    vh.recordVersion(1433,null);
    vh.recordVersion(1434,null);
    vh.recordVersion(1455,null);
    vh.recordVersion(2428,null);
    vh.recordVersion(2429,null);
    vh.recordVersion(2430,null);
    vh.recordVersion(2431,null);
    vh.recordVersion(2432,null);
    vh.recordVersion(2433,null);
    vh.recordVersion(2434,null);
    vh.recordVersion(2435,null);
    vh.recordVersion(2436,null);
    vh.recordVersion(2437,null);
    vh.recordVersion(2438,null);
    vh.recordVersion(2439,null);
    vh.recordVersion(2440,null);
    vh.recordVersion(2441,null);
    vh.recordVersion(2442,null);
    vh.recordVersion(2443,null);
    vh.recordVersion(2444,null);
    vh.recordVersion(2445,null);
    vh.recordVersion(2446,null);
    vh.recordVersion(2447,null);
    vh.recordVersion(2468,null);
    vh.recordVersion(2521,null);
    vh.recordVersion(2522,null);
    vh.recordVersion(2523,null);
    vh.recordVersion(2524,null);
    vh.recordVersion(2525,null);
    vh.recordVersion(2526,null);
    vh.recordVersion(2527,null);
    vh.recordVersion(2538,null);
    vh.recordVersion(2539,null);
    vh.recordVersion(2540,null);
    vh.recordVersion(2541,null);
    vh.recordVersion(2566,null);
    vh.recordVersion(2592,null);
    vh.recordVersion(2593,null);
    vh.recordVersion(2594,null);
    vh.recordVersion(2595,null);
    vh.recordVersion(2596,null);
    vh.recordVersion(2603,null);
    vh.recordVersion(2604,null);
    vh.recordVersion(2605,null);
    vh.recordVersion(2606,null);
    vh.recordVersion(2607,null);
    vh.recordVersion(2608,null);
    vh.recordVersion(2609,null);
    vh.recordVersion(2610,null);
    vh.recordVersion(2611,null);
    vh.recordVersion(2612,null);
    vh.recordVersion(2613,null);
    vh.recordVersion(2648,null);
    vh.recordVersion(2649,null);
    vh.recordVersion(2650,null);
    vh.recordVersion(2651,null);
    vh.recordVersion(2652,null);
    vh.recordVersion(2653,null);
    vh.recordVersion(2654,null);
    vh.recordVersion(2713,null);
    vh.recordVersion(2733,null);
    vh.recordVersion(2734,null);
    vh.recordVersion(2735,null);
    vh.recordVersion(2736,null);
    vh.recordVersion(2737,null);
    vh.recordVersion(2763,null);
    vh.recordVersion(2785,null);
    vh.recordVersion(2786,null);
    vh.recordVersion(2812,null);
    vh.recordVersion(2813,null);
    vh.recordVersion(2814,null);
    vh.recordVersion(2815,null);
    vh.recordVersion(2831,null);
    vh.recordVersion(2832,null);
    vh.recordVersion(2833,null);
    vh.recordVersion(2834,null);
    vh.recordVersion(2870,null);
    vh.recordVersion(2881,null);
    vh.recordVersion(2882,null);
    vh.recordVersion(2904,null);
    vh.recordVersion(2905,null);
    vh.recordVersion(2906,null);
    vh.recordVersion(2907,null);
    vh.recordVersion(2908,null);
    vh.recordVersion(2912,null);
    vh.recordVersion(2913,null);
    vh.recordVersion(2914,null);
    vh.recordVersion(2915,null);
    vh.recordVersion(2916,null);
    vh.recordVersion(2917,null);
    vh.recordVersion(2918,null);
    vh.recordVersion(2919,null);
    vh.recordVersion(2920,null);
    vh.recordVersion(2921,null);
    vh.recordVersion(2922,null);
    vh.recordVersion(2924,null);
    vh.recordVersion(2925,null);
    vh.recordVersion(2926,null);
    vh.recordVersion(2927,null);
    vh.recordVersion(2928,null);
    vh.recordVersion(2929,null);
    vh.recordVersion(2930,null);
    vh.recordVersion(2931,null);
    vh.recordVersion(2932,null);
    vh.recordVersion(2933,null);
    vh.recordVersion(2934,null);
    vh.recordVersion(2935,null);
    vh.recordVersion(2936,null);
    vh.recordVersion(2937,null);
    vh.recordVersion(2971,null);
    vh.recordVersion(2988,null);
    vh.recordVersion(2989,null);
    vh.recordVersion(2990,null);
    vh.recordVersion(2991,null);
    vh.recordVersion(2992,null);
    vh.recordVersion(3003,null);
    vh.recordVersion(3004,null);
    vh.recordVersion(3005,null);
    vh.recordVersion(3006,null);
    vh.recordVersion(3007,null);
    vh.recordVersion(3008,null);
    vh.recordVersion(3009,null);
    vh.recordVersion(3011,null);
    vh.recordVersion(3052,null);
    vh.recordVersion(3053,null);
    vh.recordVersion(3054,null);
    vh.recordVersion(3055,null);
    vh.recordVersion(3056,null);
    vh.recordVersion(3058,null);
    vh.recordVersion(3071,null);
    vh.recordVersion(3072,null);
    vh.recordVersion(3075,null);
    vh.recordVersion(3076,null);
    vh.recordVersion(3077,null);
    vh.recordVersion(3078,null);
    vh.recordVersion(3079,null);
    vh.recordVersion(3080,null);
    vh.recordVersion(3081,null);
    vh.recordVersion(3094,null);
    vh.recordVersion(3095,null);
    vh.recordVersion(3105,null);
    vh.recordVersion(3106,null);
    vh.recordVersion(3107,null);
    vh.recordVersion(3108,null);
    vh.recordVersion(3109,null);
    vh.recordVersion(3115,null);
    vh.recordVersion(3116,null);
    vh.recordVersion(3141,null);
    vh.recordVersion(3142,null);
    vh.recordVersion(3143,null);
    vh.recordVersion(3144,null);
    vh.recordVersion(3169,null);
    vh.recordVersion(3170,null);
    vh.recordVersion(3203,null);
    vh.recordVersion(3204,null);
    vh.recordVersion(3205,null);
    vh.recordVersion(3206,null);
    vh.recordVersion(3208,null);
    vh.recordVersion(3207,null);
    vh.recordVersion(3212,null);
    vh.recordVersion(3213,null);
    vh.recordVersion(3214,null);
    vh.recordVersion(3215,null);
    vh.recordVersion(3217,null);
    vh.recordVersion(3218,null);
    vh.recordVersion(3219,null);
    vh.recordVersion(3230,null);
    vh.recordVersion(3259,null);
    vh.recordVersion(3260,null);
    vh.recordVersion(3281,null);
    vh.recordVersion(3282,null);
    vh.recordVersion(3283,null);
    vh.recordVersion(3284,null);
    vh.recordVersion(3285,null);
    vh.recordVersion(3286,null);
    vh.recordVersion(3287,null);
    vh.recordVersion(3288,null);
    vh.recordVersion(3289,null);
    vh.recordVersion(3290,null);
    vh.recordVersion(3296,null);
    vh.recordVersion(3297,null);
    vh.recordVersion(3298,null);
    vh.recordVersion(3302,null);
    vh.recordVersion(3324,null);
    vh.recordVersion(3370,null);
    vh.recordVersion(3371,null);
    vh.recordVersion(3372,null);
    vh.recordVersion(3398,null);
    vh.recordVersion(3399,null);
    vh.recordVersion(3419,null);
    vh.recordVersion(3420,null);
    vh.recordVersion(3422,null);
    vh.recordVersion(3423,null);
    vh.recordVersion(3424,null);
    vh.recordVersion(3425,null);
    vh.recordVersion(3426,null);
    vh.recordVersion(3427,null);
    vh.recordVersion(3428,null);
    vh.recordVersion(3429,null);
    vh.recordVersion(3445,null);
    vh.recordVersion(3446,null);
    vh.recordVersion(3447,null);
    vh.recordVersion(3448,null);
    vh.recordVersion(3464,null);
    vh.recordVersion(3465,null);
    vh.recordVersion(3466,null);
    vh.recordVersion(3467,null);
    vh.recordVersion(3468,null);
    vh.recordVersion(3469,null);
    vh.recordVersion(3470,null);
    vh.recordVersion(3471,null);
    vh.recordVersion(3472,null);
    vh.recordVersion(3473,null);
    vh.recordVersion(3478,null);
    vh.recordVersion(3500,null);
    vh.recordVersion(3501,null);
    vh.recordVersion(3537,null);
    vh.recordVersion(3538,null);
    vh.recordVersion(3559,null);
    vh.recordVersion(3566,null);
    vh.recordVersion(3594,null);
    vh.recordVersion(3595,null);
    vh.recordVersion(3596,null);
    vh.recordVersion(3597,null);
    vh.recordVersion(3662,null);
    vh.recordVersion(3663,null);
    vh.recordVersion(3664,null);
    vh.recordVersion(3665,null);
    vh.recordVersion(3666,null);
    vh.recordVersion(3667,null);
    vh.recordVersion(3668,null);
    vh.recordVersion(3669,null);
    vh.recordVersion(3670,null);
    vh.recordVersion(3673,null);
    vh.recordVersion(3674,null);
    vh.recordVersion(3691,null);
    vh.recordVersion(3692,null);
    vh.recordVersion(3693,null);
    vh.recordVersion(3694,null);
    vh.recordVersion(3720,null);
    vh.recordVersion(3721,null);
    vh.recordVersion(3722,null);
    vh.recordVersion(3723,null);
    vh.recordVersion(3724,null);
    vh.recordVersion(3725,null);
    vh.recordVersion(3757,null);
    vh.recordVersion(3765,null);
    vh.recordVersion(3766,null);
    vh.recordVersion(3767,null);
    vh.recordVersion(3768,null);
    vh.recordVersion(3769,null);
    vh.recordVersion(3770,null);
    vh.recordVersion(3771,null);
    vh.recordVersion(3772,null);
    vh.recordVersion(3773,null);
    vh.recordVersion(3774,null);
    vh.recordVersion(3775,null);
    vh.recordVersion(3776,null);
    vh.recordVersion(3777,null);
    vh.recordVersion(3778,null);
    vh.recordVersion(3779,null);
    vh.recordVersion(3796,null);
    vh.recordVersion(3797,null);
    vh.recordVersion(3798,null);
    vh.recordVersion(3799,null);
    vh.recordVersion(3800,null);
    vh.recordVersion(3821,null);
    vh.recordVersion(3822,null);
    vh.recordVersion(3823,null);
    vh.recordVersion(3840,null);
    vh.recordVersion(3863,null);
    vh.recordVersion(3864,null);
    vh.recordVersion(3865,null);
    vh.recordVersion(3867,null);
    vh.recordVersion(3866,null);
    vh.recordVersion(3890,null);
    vh.recordVersion(3891,null);
    vh.recordVersion(3892,null);
    vh.recordVersion(3893,null);
    vh.recordVersion(3894,null);
    vh.recordVersion(3895,null);
    vh.recordVersion(3915,null);
    vh.recordVersion(3916,null);
    vh.recordVersion(3917,null);
    vh.recordVersion(3918,null);
    vh.recordVersion(3919,null);
    vh.recordVersion(3920,null);
    vh.recordVersion(3929,null);
    vh.recordVersion(3948,null);
    vh.recordVersion(3955,null);
    vh.recordVersion(3975,null);
    vh.recordVersion(3976,null);
    vh.recordVersion(3984,null);
    vh.recordVersion(3985,null);
    vh.recordVersion(3986,null);
    vh.recordVersion(3987,null);
    vh.recordVersion(3990,null);
    vh.recordVersion(4007,null);
    vh.recordVersion(4008,null);
    vh.recordVersion(4009,null);
    vh.recordVersion(4010,null);
    vh.recordVersion(4025,null);
    vh.recordVersion(4026,null);
    vh.recordVersion(4027,null);
    vh.recordVersion(4047,null);
    vh.recordVersion(4067,null);
    vh.recordVersion(4068,null);
    vh.recordVersion(4088,null);
    vh.recordVersion(4104,null);
    vh.recordVersion(4105,null);
    vh.recordVersion(4106,null);
    vh.recordVersion(4114,null);
    vh.recordVersion(4126,null);
    vh.recordVersion(4167,null);
    vh.recordVersion(4175,null);
    vh.recordVersion(4176,null);
    vh.recordVersion(4177,null);
    vh.recordVersion(4179,null);
    vh.recordVersion(4178,null);
    vh.recordVersion(4180,null);
    vh.recordVersion(4187,null);
    vh.recordVersion(4188,null);
    vh.recordVersion(4189,null);
    vh.recordVersion(4190,null);
    vh.recordVersion(4191,null);
    vh.recordVersion(4192,null);
    vh.recordVersion(4193,null);
    vh.recordVersion(4194,null);
    vh.recordVersion(4195,null);
    vh.recordVersion(4196,null);
    vh.recordVersion(4197,null);
    vh.recordVersion(4198,null);
    vh.recordVersion(4199,null);
    vh.recordVersion(4200,null);
    vh.recordVersion(4201,null);
    vh.recordVersion(4202,null);
    vh.recordVersion(4208,null);
    vh.recordVersion(4209,null);
    vh.recordVersion(4275,null);
    vh.recordVersion(4276,null);
    vh.recordVersion(4277,null);
    vh.recordVersion(4278,null);
    vh.recordVersion(4279,null);
    vh.recordVersion(4280,null);
    vh.recordVersion(4281,null);
    vh.recordVersion(4282,null);
    vh.recordVersion(4283,null);
    vh.recordVersion(4284,null);
    vh.recordVersion(4303,null);
    vh.recordVersion(4304,null);
    vh.recordVersion(4305,null);
    vh.recordVersion(4306,null);
    vh.recordVersion(4320,null);
    vh.recordVersion(4321,null);
    vh.recordVersion(4322,null);
    vh.recordVersion(4323,null);
    vh.recordVersion(4324,null);
    vh.recordVersion(4325,null);
    vh.recordVersion(4326,null);
    vh.recordVersion(4327,null);
    vh.recordVersion(4328,null);
    vh.recordVersion(4352,null);
    vh.recordVersion(4353,null);
    vh.recordVersion(4354,null);
    vh.recordVersion(4355,null);
    vh.recordVersion(4356,null);
    vh.recordVersion(4374,null);
    vh.recordVersion(4375,null);
    vh.recordVersion(4376,null);
    vh.recordVersion(4377,null);
    vh.recordVersion(4378,null);
    vh.recordVersion(4379,null);
    vh.recordVersion(4380,null);
    vh.recordVersion(4381,null);
    vh.recordVersion(4382,null);
    vh.recordVersion(4383,null);
    vh.recordVersion(4384,null);
    vh.recordVersion(4385,null);
    vh.recordVersion(4386,null);
    vh.recordVersion(4387,null);
    vh.recordVersion(4388,null);
    vh.recordVersion(4429,null);
    vh.recordVersion(4430,null);
    vh.recordVersion(4431,null);
    vh.recordVersion(4432,null);
    vh.recordVersion(4433,null);
    vh.recordVersion(4434,null);
    vh.recordVersion(4435,null);
    vh.recordVersion(4436,null);
    vh.recordVersion(4438,null);
    vh.recordVersion(4439,null);
    vh.recordVersion(4440,null);
    vh.recordVersion(4441,null);
    vh.recordVersion(4442,null);
    vh.recordVersion(4443,null);
    vh.recordVersion(4444,null);
    vh.recordVersion(4445,null);
    vh.recordVersion(4446,null);
    vh.recordVersion(4447,null);
    vh.recordVersion(4448,null);
    vh.recordVersion(4449,null);
    vh.recordVersion(4468,null);
    vh.recordVersion(4471,null);
    vh.recordVersion(4472,null);
    vh.recordVersion(4483,null);
    vh.recordVersion(4484,null);
    vh.recordVersion(4485,null);
    vh.recordVersion(4486,null);
    vh.recordVersion(4487,null);
    vh.recordVersion(4489,null);
    vh.recordVersion(4488,null);
    vh.recordVersion(4490,null);
    vh.recordVersion(4491,null);
    vh.recordVersion(4492,null);
    vh.recordVersion(4493,null);
    vh.recordVersion(4501,null);
    vh.recordVersion(4540,null);
    vh.recordVersion(4541,null);
    vh.recordVersion(4542,null);
    vh.recordVersion(4543,null);
    vh.recordVersion(4544,null);
    vh.recordVersion(4545,null);
    vh.recordVersion(4546,null);
    vh.recordVersion(4547,null);
    vh.recordVersion(4567,null);
    vh.recordVersion(4568,null);
    vh.recordVersion(4569,null);
    vh.recordVersion(4570,null);
    vh.recordVersion(4571,null);
    vh.recordVersion(4572,null);
    vh.recordVersion(4573,null);
    vh.recordVersion(4574,null);
    vh.recordVersion(4575,null);
    vh.recordVersion(4600,null);
    vh.recordVersion(4601,null);
    vh.recordVersion(4602,null);
    vh.recordVersion(4603,null);
    vh.recordVersion(4616,null);
    vh.recordVersion(4617,null);
    vh.recordVersion(4620,null);
    vh.recordVersion(4621,null);
    vh.recordVersion(4622,null);
    vh.recordVersion(4623,null);
    vh.recordVersion(4624,null);
    vh.recordVersion(4625,null);
    vh.recordVersion(4626,null);
    vh.recordVersion(4627,null);
    vh.recordVersion(4629,null);
    vh.recordVersion(4630,null);
    vh.recordVersion(4631,null);
    vh.recordVersion(4632,null);
    vh.recordVersion(4633,null);
    vh.recordVersion(4634,null);
    vh.recordVersion(4658,null);
    vh.recordVersion(4659,null);
    vh.recordVersion(4678,null);
    vh.recordVersion(4691,null);
    vh.recordVersion(4711,null);
    vh.recordVersion(4737,null);
    vh.recordVersion(4738,null);
    vh.recordVersion(4739,null);
    vh.recordVersion(4740,null);
    vh.recordVersion(4745,null);
    vh.recordVersion(4746,null);
    vh.recordVersion(4747,null);
    vh.recordVersion(4748,null);
    vh.recordVersion(4761,null);
    vh.recordVersion(4762,null);
    vh.recordVersion(4763,null);
    vh.recordVersion(4779,null);
    vh.recordVersion(4780,null);
    vh.recordVersion(4781,null);
    vh.recordVersion(4782,null);
    vh.recordVersion(4807,null);
    vh.recordVersion(4808,null);
    vh.recordVersion(4809,null);
    vh.recordVersion(4810,null);
    vh.recordVersion(4811,null);
    vh.recordVersion(4812,null);
    vh.recordVersion(4813,null);
    vh.recordVersion(4814,null);
    vh.recordVersion(4840,null);
    vh.recordVersion(4859,null);
    vh.recordVersion(4877,null);
    vh.recordVersion(4878,null);
    vh.recordVersion(4879,null);
    vh.recordVersion(4905,null);
    vh.recordVersion(4906,null);
    vh.recordVersion(4910,null);
    vh.recordVersion(4911,null);
    vh.recordVersion(4948,null);
    vh.recordVersion(4949,null);
    vh.recordVersion(4950,null);
    vh.recordVersion(4951,null);
    vh.recordVersion(4952,null);
    vh.recordVersion(4953,null);
    vh.recordVersion(4954,null);
    vh.recordVersion(4955,null);
    vh.recordVersion(4956,null);
    vh.recordVersion(4964,null);
    vh.recordVersion(4976,null);
    vh.recordVersion(4977,null);
    vh.recordVersion(4978,null);
    vh.recordVersion(4979,null);
    vh.recordVersion(4980,null);
    vh.recordVersion(5016,null);
    vh.recordVersion(5026,null);
    vh.recordVersion(5027,null);
    vh.recordVersion(5028,null);
    vh.recordVersion(5029,null);
    vh.recordVersion(5030,null);
    vh.recordVersion(5052,null);
    vh.recordVersion(5053,null);
    vh.recordVersion(5071,null);
    vh.recordVersion(5072,null);
    vh.recordVersion(5094,null);
    vh.recordVersion(5095,null);
    vh.recordVersion(5096,null);
    vh.recordVersion(5097,null);
    vh.recordVersion(5098,null);
    vh.recordVersion(5099,null);
    vh.recordVersion(5100,null);
    vh.recordVersion(5101,null);
    vh.recordVersion(5102,null);
    vh.recordVersion(5103,null);
    vh.recordVersion(5104,null);
    vh.recordVersion(5105,null);
    vh.recordVersion(5106,null);
    vh.recordVersion(5107,null);
    vh.recordVersion(5108,null);
    vh.recordVersion(5109,null);
    vh.recordVersion(5110,null);
    vh.recordVersion(5112,null);
    vh.recordVersion(5138,null);
    vh.recordVersion(5139,null);
    vh.recordVersion(5140,null);
    vh.recordVersion(5141,null);
    vh.recordVersion(5142,null);
    vh.recordVersion(5143,null);
    vh.recordVersion(5144,null);
    vh.recordVersion(5145,null);
    vh.recordVersion(5146,null);
    vh.recordVersion(5147,null);
    vh.recordVersion(5148,null);
    vh.recordVersion(5149,null);
    vh.recordVersion(5150,null);
    vh.recordVersion(5151,null);
    vh.recordVersion(5152,null);
    vh.recordVersion(5163,null);
    vh.recordVersion(5164,null);
    vh.recordVersion(5165,null);
    vh.recordVersion(5166,null);
    vh.recordVersion(5167,null);
    vh.recordVersion(5168,null);
    vh.recordVersion(5169,null);
    vh.recordVersion(5170,null);
    vh.recordVersion(5171,null);
    vh.recordVersion(5172,null);
    vh.recordVersion(5173,null);
    vh.recordVersion(5174,null);
    vh.recordVersion(5175,null);
    vh.recordVersion(5176,null);
    vh.recordVersion(5184,null);
    vh.recordVersion(5185,null);
    vh.recordVersion(5186,null);
    vh.recordVersion(5187,null);
    vh.recordVersion(5205,null);
    vh.recordVersion(5206,null);
    vh.recordVersion(5207,null);
    vh.recordVersion(5208,null);
    vh.recordVersion(5209,null);
    vh.recordVersion(5210,null);
    vh.recordVersion(5211,null);
    vh.recordVersion(5212,null);
    vh.recordVersion(5213,null);
    vh.recordVersion(5214,null);
    vh.recordVersion(5215,null);
    vh.recordVersion(5216,null);
    vh.recordVersion(5217,null);
    vh.recordVersion(5218,null);
    vh.recordVersion(5219,null);
    vh.recordVersion(5221,null);
    vh.recordVersion(5220,null);
    vh.recordVersion(5222,null);
    vh.recordVersion(5224,null);
    vh.recordVersion(5223,null);
    vh.recordVersion(5225,null);
    vh.recordVersion(5226,null);
    vh.recordVersion(5227,null);
    vh.recordVersion(5228,null);
    vh.recordVersion(5229,null);
    vh.recordVersion(5252,null);
    vh.recordVersion(5269,null);
    vh.recordVersion(5270,null);
    vh.recordVersion(5271,null);
    vh.recordVersion(5272,null);
    vh.recordVersion(5273,null);
    vh.recordVersion(5314,null);
    vh.recordVersion(5315,null);
    vh.recordVersion(5316,null);
    vh.recordVersion(5317,null);
    vh.recordVersion(5336,null);
    vh.recordVersion(5337,null);
    vh.recordVersion(5345,null);
    vh.recordVersion(5346,null);
    vh.recordVersion(5347,null);
    vh.recordVersion(5348,null);
    vh.recordVersion(5354,null);
    vh.recordVersion(5363,null);
    vh.recordVersion(5364,null);
    vh.recordVersion(5365,null);
    vh.recordVersion(5366,null);
    vh.recordVersion(5367,null);
    vh.recordVersion(5369,null);
    vh.recordVersion(5368,null);
    vh.recordVersion(5370,null);
    vh.recordVersion(5371,null);
    vh.recordVersion(5372,null);
    vh.recordVersion(5380,null);
    vh.recordVersion(5381,null);
    vh.recordVersion(5382,null);
    vh.recordVersion(5383,null);
    vh.recordVersion(5384,null);
    vh.recordVersion(5385,null);
    vh.recordVersion(5386,null);
    vh.recordVersion(5387,null);
    vh.recordVersion(5406,null);
    vh.recordVersion(5407,null);
    vh.recordVersion(5408,null);
    vh.recordVersion(5410,null);
    vh.recordVersion(5409,null);
    vh.recordVersion(5411,null);
    vh.recordVersion(5427,null);
    vh.recordVersion(5428,null);
    vh.recordVersion(5429,null);
    vh.recordVersion(5430,null);
    vh.recordVersion(5431,null);
    vh.recordVersion(5449,null);
    vh.recordVersion(5450,null);
    vh.recordVersion(5451,null);
    vh.recordVersion(5452,null);
    vh.recordVersion(5453,null);
    vh.recordVersion(5454,null);
    vh.recordVersion(5475,null);
    vh.recordVersion(5476,null);
    vh.recordVersion(5477,null);
    vh.recordVersion(5478,null);
    vh.recordVersion(5524,null);
    vh.recordVersion(5531,null);
    vh.recordVersion(5532,null);
    vh.recordVersion(5533,null);
    vh.recordVersion(5534,null);
    vh.recordVersion(5535,null);
    vh.recordVersion(5536,null);
    vh.recordVersion(5537,null);
    vh.recordVersion(5538,null);
    vh.recordVersion(5539,null);
    vh.recordVersion(5580,null);
    vh.recordVersion(5581,null);
    vh.recordVersion(5587,null);
    vh.recordVersion(5588,null);
    vh.recordVersion(5589,null);
    vh.recordVersion(5590,null);
    vh.recordVersion(5591,null);
    vh.recordVersion(5592,null);
    vh.recordVersion(5593,null);
    vh.recordVersion(5594,null);
    vh.recordVersion(5595,null);
    vh.recordVersion(5596,null);
    vh.recordVersion(5597,null);
    vh.recordVersion(5598,null);
    vh.recordVersion(5599,null);
    vh.recordVersion(5600,null);
    vh.recordVersion(5601,null);
    vh.recordVersion(5602,null);
    vh.recordVersion(5628,null);
    vh.recordVersion(5629,null);
    vh.recordVersion(5652,null);
    vh.recordVersion(5653,null);
    vh.recordVersion(5654,null);
    vh.recordVersion(5655,null);
    vh.recordVersion(5656,null);
    vh.recordVersion(5668,null);
    vh.recordVersion(5669,null);
    vh.recordVersion(5698,null);
    vh.recordVersion(5699,null);
    vh.recordVersion(5700,null);
    vh.recordVersion(5701,null);
    vh.recordVersion(5702,null);
    vh.recordVersion(5711,null);
    vh.recordVersion(5712,null);
    vh.recordVersion(5713,null);
    vh.recordVersion(5714,null);
    vh.recordVersion(5715,null);
    vh.recordVersion(5716,null);
    vh.recordVersion(5717,null);
    vh.recordVersion(5718,null);
    vh.recordVersion(5719,null);
    vh.recordVersion(5737,null);
    vh.recordVersion(5763,null);
    vh.recordVersion(5786,null);
    vh.recordVersion(5787,null);
    vh.recordVersion(5788,null);
    vh.recordVersion(5807,null);
    vh.recordVersion(5808,null);
    vh.recordVersion(5831,null);
    vh.recordVersion(5832,null);
    vh.recordVersion(5856,null);
    vh.recordVersion(5857,null);
    vh.recordVersion(5864,null);
    vh.recordVersion(5865,null);
    vh.recordVersion(5866,null);
    vh.recordVersion(5867,null);
    vh.recordVersion(5868,null);
    vh.recordVersion(5869,null);
    vh.recordVersion(5870,null);
    vh.recordVersion(5898,null);
    vh.recordVersion(5899,null);
    vh.recordVersion(5900,null);
    vh.recordVersion(5901,null);
    vh.recordVersion(5902,null);
    vh.recordVersion(5903,null);
    vh.recordVersion(5908,null);
    vh.recordVersion(5923,null);
    vh.recordVersion(5924,null);
    vh.recordVersion(5925,null);
    vh.recordVersion(5926,null);
    vh.recordVersion(5927,null);
    vh.recordVersion(5928,null);
    vh.recordVersion(5929,null);
    vh.recordVersion(5930,null);
    vh.recordVersion(5931,null);
    vh.recordVersion(5932,null);
    vh.recordVersion(5933,null);
    vh.recordVersion(5940,null);
    vh.recordVersion(5941,null);
    vh.recordVersion(5942,null);
    vh.recordVersion(5943,null);
    vh.recordVersion(5944,null);
    vh.recordVersion(5945,null);
    vh.recordVersion(5968,null);
    vh.recordVersion(5969,null);
    vh.recordVersion(5970,null);
    vh.recordVersion(5971,null);
    vh.recordVersion(5972,null);
    vh.recordVersion(5973,null);
    vh.recordVersion(5974,null);
    vh.recordVersion(5975,null);
    vh.recordVersion(6032,null);
    vh.recordVersion(6048,null);
    vh.recordVersion(6049,null);
    vh.recordVersion(6050,null);
    vh.recordVersion(6051,null);
    vh.recordVersion(6063,null);
    vh.recordVersion(6064,null);
    vh.recordVersion(6065,null);
    vh.recordVersion(6066,null);
    vh.recordVersion(6067,null);
    vh.recordVersion(6068,null);
    vh.recordVersion(6084,null);
    vh.recordVersion(6098,null);
    vh.recordVersion(6099,null);
    vh.recordVersion(6123,null);
    vh.recordVersion(6124,null);
    vh.recordVersion(6125,null);
    vh.recordVersion(6126,null);
    vh.recordVersion(6127,null);
    vh.recordVersion(6128,null);
    vh.recordVersion(6129,null);
    vh.recordVersion(6141,null);
    vh.recordVersion(6169,null);
    vh.recordVersion(6170,null);
    vh.recordVersion(6171,null);
    vh.recordVersion(6190,null);
    vh.recordVersion(6199,null);
    vh.recordVersion(6200,null);
    vh.recordVersion(6218,null);
    vh.recordVersion(6219,null);
    vh.recordVersion(6220,null);
    vh.recordVersion(6221,null);
    vh.recordVersion(6222,null);
    vh.recordVersion(6223,null);
    vh.recordVersion(6224,null);
    vh.recordVersion(6225,null);
    vh.recordVersion(6293,null);
    vh.recordVersion(6302,null);
    vh.recordVersion(6303,null);
    vh.recordVersion(6304,null);
    vh.recordVersion(6305,null);
    vh.recordVersion(6306,null);
    vh.recordVersion(6307,null);
    vh.recordVersion(6308,null);
    vh.recordVersion(6323,null);
    vh.recordVersion(6322,null);
    vh.recordVersion(6325,null);
    vh.recordVersion(6324,null);
    vh.recordVersion(6326,null);
    vh.recordVersion(6327,null);
    vh.recordVersion(6328,null);
    vh.recordVersion(6329,null);
    vh.recordVersion(6347,null);
    vh.recordVersion(6348,null);
    vh.recordVersion(6349,null);
    vh.recordVersion(6350,null);
    vh.recordVersion(6351,null);
    vh.recordVersion(6352,null);
    vh.recordVersion(6353,null);
    vh.recordVersion(6354,null);
    vh.recordVersion(6355,null);
    vh.recordVersion(6356,null);
    vh.recordVersion(6357,null);
    vh.recordVersion(6358,null);
    vh.recordVersion(6359,null);
    vh.recordVersion(6360,null);
    vh.recordVersion(6361,null);
    vh.recordVersion(6362,null);
    vh.recordVersion(6387,null);
    vh.recordVersion(6388,null);
    vh.recordVersion(6398,null);
    vh.recordVersion(6399,null);
    vh.recordVersion(6400,null);
    vh.recordVersion(6401,null);
    vh.recordVersion(6422,null);
    vh.recordVersion(6430,null);
    vh.recordVersion(6431,null);
    vh.recordVersion(6432,null);
    vh.recordVersion(6433,null);
    vh.recordVersion(6434,null);
    vh.recordVersion(6435,null);
    vh.recordVersion(6436,null);
    vh.recordVersion(6450,null);
    vh.recordVersion(6451,null);
    vh.recordVersion(6452,null);
    vh.recordVersion(6456,null);
    vh.recordVersion(6457,null);
    vh.recordVersion(6458,null);
    vh.recordVersion(6459,null);
    vh.recordVersion(6460,null);
    vh.recordVersion(6461,null);
    vh.recordVersion(6462,null);
    vh.recordVersion(6463,null);
    vh.recordVersion(6466,null);
    vh.recordVersion(6467,null);
    vh.recordVersion(6468,null);
    vh.recordVersion(6474,null);
    vh.recordVersion(6475,null);
    vh.recordVersion(6476,null);
    vh.recordVersion(6477,null);
    vh.recordVersion(6478,null);
    vh.recordVersion(6479,null);
    vh.recordVersion(6480,null);
    vh.recordVersion(6481,null);
    vh.recordVersion(6482,null);
    vh.recordVersion(6483,null);
    vh.recordVersion(6484,null);
    vh.recordVersion(6485,null);
    vh.recordVersion(6486,null);
    vh.recordVersion(6487,null);
    vh.recordVersion(6488,null);
    vh.recordVersion(6489,null);
    vh.recordVersion(6528,null);
    vh.recordVersion(6581,null);
    vh.recordVersion(6596,null);
    vh.recordVersion(6597,null);
    vh.recordVersion(6598,null);
    vh.recordVersion(6599,null);
    vh.recordVersion(6600,null);
    vh.recordVersion(6601,null);
    vh.recordVersion(6602,null);
    vh.recordVersion(6628,null);
    vh.recordVersion(6676,null);
    vh.recordVersion(6690,null);
    vh.recordVersion(6704,null);
    vh.recordVersion(6705,null);
    vh.recordVersion(6706,null);
    vh.recordVersion(6768,null);
    vh.recordVersion(6769,null);
    vh.recordVersion(6770,null);
    vh.recordVersion(6771,null);
    vh.recordVersion(6772,null);
    vh.recordVersion(6773,null);
    vh.recordVersion(6774,null);
    vh.recordVersion(6776,null);
    vh.recordVersion(6777,null);
    vh.recordVersion(6778,null);
    vh.recordVersion(6779,null);
    vh.recordVersion(6790,null);
    vh.recordVersion(6791,null);
    vh.recordVersion(6792,null);
    vh.recordVersion(6793,null);
    vh.recordVersion(6794,null);
    vh.recordVersion(6795,null);
    vh.recordVersion(6796,null);
    vh.recordVersion(6797,null);
    vh.recordVersion(6798,null);
    vh.recordVersion(6816,null);
    vh.recordVersion(6817,null);
    vh.recordVersion(6818,null);
    vh.recordVersion(6819,null);
    vh.recordVersion(6820,null);
    vh.recordVersion(6821,null);
    vh.recordVersion(6844,null);
    vh.recordVersion(6843,null);
    vh.recordVersion(6845,null);
    vh.recordVersion(6846,null);
    vh.recordVersion(6847,null);
    vh.recordVersion(6848,null);
    vh.recordVersion(6849,null);
    vh.recordVersion(6850,null);
    vh.recordVersion(6852,null);
    vh.recordVersion(6853,null);
    vh.recordVersion(6855,null);
    vh.recordVersion(6854,null);
    vh.recordVersion(6878,null);
    vh.recordVersion(6884,null);
    vh.recordVersion(6885,null);
    vh.recordVersion(6908,null);
    vh.recordVersion(6907,null);
    vh.recordVersion(6909,null);
    vh.recordVersion(6910,null);
    vh.recordVersion(6911,null);
    vh.recordVersion(6912,null);
    vh.recordVersion(6913,null);
    vh.recordVersion(6936,null);
    vh.recordVersion(6937,null);
    vh.recordVersion(6938,null);
    vh.recordVersion(6939,null);
    vh.recordVersion(6967,null);
    vh.recordVersion(6968,null);
    vh.recordVersion(6989,null);
    vh.recordVersion(7006,null);
    vh.recordVersion(7021,null);
    vh.recordVersion(7022,null);
    vh.recordVersion(7023,null);
    vh.recordVersion(7024,null);
    vh.recordVersion(7028,null);
    vh.recordVersion(7060,null);
    vh.recordVersion(7061,null);
    vh.recordVersion(7062,null);
    vh.recordVersion(7063,null);
    vh.recordVersion(7064,null);
    vh.recordVersion(7065,null);
    vh.recordVersion(7066,null);
    vh.recordVersion(7067,null);
    vh.recordVersion(7068,null);
    vh.recordVersion(7069,null);
    vh.recordVersion(7070,null);
    vh.recordVersion(7071,null);
    vh.recordVersion(7072,null);
    vh.recordVersion(7073,null);
    vh.recordVersion(7074,null);
    vh.recordVersion(7075,null);
    vh.recordVersion(7076,null);
    vh.recordVersion(7094,null);
    vh.recordVersion(7095,null);
    vh.recordVersion(7104,null);
    vh.recordVersion(7105,null);
    vh.recordVersion(7106,null);
    vh.recordVersion(7107,null);
    vh.recordVersion(7108,null);
    vh.recordVersion(7109,null);
    vh.recordVersion(7111,null);
    vh.recordVersion(7110,null);
    vh.recordVersion(7112,null);
    vh.recordVersion(7113,null);
    vh.recordVersion(7114,null);
    vh.recordVersion(7115,null);
    vh.recordVersion(7116,null);
    vh.recordVersion(7117,null);
    vh.recordVersion(7118,null);
    vh.recordVersion(7119,null);
    vh.recordVersion(7120,null);
    vh.recordVersion(7121,null);
    vh.recordVersion(7122,null);
    vh.recordVersion(7123,null);
    vh.recordVersion(7134,null);
    vh.recordVersion(7135,null);
    vh.recordVersion(7136,null);
    vh.recordVersion(7137,null);
    vh.recordVersion(7138,null);
    vh.recordVersion(7187,null);
    vh.recordVersion(7188,null);
    vh.recordVersion(7189,null);
    vh.recordVersion(7190,null);
    vh.recordVersion(7191,null);
    vh.recordVersion(7192,null);
    vh.recordVersion(7193,null);
    vh.recordVersion(7194,null);
    vh.recordVersion(7195,null);
    vh.recordVersion(7196,null);
    vh.recordVersion(7221,null);
    vh.recordVersion(7222,null);
    vh.recordVersion(7223,null);
    vh.recordVersion(7234,null);
    vh.recordVersion(7235,null);
    vh.recordVersion(7236,null);
    vh.recordVersion(7237,null);
    vh.recordVersion(7238,null);
    vh.recordVersion(7244,null);
    vh.recordVersion(7246,null);
    vh.recordVersion(7245,null);
    vh.recordVersion(7247,null);
    vh.recordVersion(7248,null);
    vh.recordVersion(7249,null);
    vh.recordVersion(7280,null);
    vh.recordVersion(7281,null);
    vh.recordVersion(7282,null);
    vh.recordVersion(7283,null);
    vh.recordVersion(7303,null);
    vh.recordVersion(7304,null);
    vh.recordVersion(7305,null);
    vh.recordVersion(7306,null);
    vh.recordVersion(7307,null);
    vh.recordVersion(7308,null);
    vh.recordVersion(7309,null);
    vh.recordVersion(7310,null);
    vh.recordVersion(7336,null);
    vh.recordVersion(7340,null);
    vh.recordVersion(7383,null);
    vh.recordVersion(7400,null);
    vh.recordVersion(7422,null);
    vh.recordVersion(7441,null);
    vh.recordVersion(7442,null);
    vh.recordVersion(7443,null);
    vh.recordVersion(7444,null);
    vh.recordVersion(7445,null);
    vh.recordVersion(7446,null);
    vh.recordVersion(7447,null);
    vh.recordVersion(7448,null);
    vh.recordVersion(7449,null);
    vh.recordVersion(7450,null);
    vh.recordVersion(7451,null);
    vh.recordVersion(7452,null);
    vh.recordVersion(7453,null);
    vh.recordVersion(7454,null);
    vh.recordVersion(7455,null);
    vh.recordVersion(7456,null);
    vh.recordVersion(7457,null);
    vh.recordVersion(7458,null);
    vh.recordVersion(7477,null);
    vh.recordVersion(7506,null);
    vh.recordVersion(7507,null);
    vh.recordVersion(7508,null);
    vh.recordVersion(7509,null);
    vh.recordVersion(7515,null);
    vh.recordVersion(7516,null);
    vh.recordVersion(7517,null);
    vh.recordVersion(7518,null);
    vh.recordVersion(7519,null);
    vh.recordVersion(7521,null);
    vh.recordVersion(7520,null);
    vh.recordVersion(7522,null);
    vh.recordVersion(7523,null);
    vh.recordVersion(7524,null);
    vh.recordVersion(7525,null);
    vh.recordVersion(7526,null);
    vh.recordVersion(7527,null);
    vh.recordVersion(7528,null);
    vh.recordVersion(7530,null);
    vh.recordVersion(7531,null);
    vh.recordVersion(7532,null);
    vh.recordVersion(7533,null);
    vh.recordVersion(7534,null);
    vh.recordVersion(7535,null);
    vh.recordVersion(7536,null);
    vh.recordVersion(7537,null);
    vh.recordVersion(7538,null);
    vh.recordVersion(7539,null);
    vh.recordVersion(7540,null);
    vh.recordVersion(7563,null);
    vh.recordVersion(7564,null);
    vh.recordVersion(7565,null);
    vh.recordVersion(7566,null);
    vh.recordVersion(7567,null);
    vh.recordVersion(7568,null);
    vh.recordVersion(7569,null);
    vh.recordVersion(7570,null);
    vh.recordVersion(7571,null);
    vh.recordVersion(7572,null);
    vh.recordVersion(7573,null);
    vh.recordVersion(7574,null);
    vh.recordVersion(7586,null);
    vh.recordVersion(7587,null);
    vh.recordVersion(7588,null);
    vh.recordVersion(7589,null);
    vh.recordVersion(7590,null);
    vh.recordVersion(7591,null);
    vh.recordVersion(7592,null);
    vh.recordVersion(7593,null);
    vh.recordVersion(7594,null);
    vh.recordVersion(7603,null);
    vh.recordVersion(7604,null);
    vh.recordVersion(7610,null);
    vh.recordVersion(7611,null);
    vh.recordVersion(7612,null);
    vh.recordVersion(7613,null);
    vh.recordVersion(7623,null);
    vh.recordVersion(7624,null);
    vh.recordVersion(7625,null);
    vh.recordVersion(7656,null);
    vh.recordVersion(7659,null);
    vh.recordVersion(7676,null);
    vh.recordVersion(7677,null);
    vh.recordVersion(7678,null);
    vh.recordVersion(7679,null);
    vh.recordVersion(7680,null);
    vh.recordVersion(7704,null);
    vh.recordVersion(7726,null);
    vh.recordVersion(7727,null);
    vh.recordVersion(7728,null);
    vh.recordVersion(7729,null);
    vh.recordVersion(7730,null);
    vh.recordVersion(7731,null);
    vh.recordVersion(7732,null);
    vh.recordVersion(7733,null);
    vh.recordVersion(7734,null);
    vh.recordVersion(7735,null);
    vh.recordVersion(7736,null);
    vh.recordVersion(7744,null);
    vh.recordVersion(7745,null);
    vh.recordVersion(7746,null);
    System.out.println("updated:\t" +vh);
    vh.removeExceptionsOlderThan(8712);
    System.out.println("after GC:\t" +vh);


    RegionVersionHolder clone = vh.clone();
    System.out.println("clone: \t\t" + clone);
    assertTrue("Expected a version greater than 7746 but got this: " + clone, clone.getVersion() >=7746);
    
  }

  /**
   * Test merging two version holders
   */
  public void testInitializeFrom() {
    testInitializeFrom(false);
    testInitializeFrom(true);
  }
  
  private void testInitializeFrom(boolean withTreeSets) {
    RVVException.UseTreeSetsForTesting = withTreeSets;
    try {
      //Create a bit set that represents some seen version
      //with some exceptions
      BitSet bs1 = new BitSet();
      bs1.set(1, 6);
      bs1.set(10, 13);
      bs1.set(30, 51);
      bs1.set(60, 66);
      bs1.set(68, 101);
      
      //Get a version holder that has seen these exceptions
      RegionVersionHolder vh1 = buildHolder(bs1);
      validateExceptions(vh1);
      
  
      {
        //Simple case, initialize an empty version holder
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
        vh2.initializeFrom(vh1);
  
        assertEquals("RVV=" + vh2, 100, vh2.getVersion());
  
        compareWithBitSet(bs1, vh2);
        validateExceptions(vh2);
      }
      
      {
        //Initialize a vector that has seen some later versions, and has some older
        //exceptions
        
        BitSet bs2 = new BitSet();
        
        //Add an exception that doesn't overlap with vh1 exceptions
        bs2.set(80, 106);
        bs2.set(72, 74);
        
        //Add an exception that is contained within a vh1 exception
        bs2.set(59,70);
        bs2.set(57);
        
        //Add exceptions that partially overlap a vh1 exception on either end
        bs2.set(35,56);
        bs2.set(15,26);
        bs2.set(1,4);
        
        RegionVersionHolder vh2 = buildHolder(bs2);
        
        validateExceptions(vh2);

        vh2.initializeFrom(vh1);
        
        assertEquals(105, vh2.version);
        assertEquals(100, vh2.getVersion());
        compareWithBitSet(bs1, vh2);
        
        RegionVersionHolder vh4 = vh2.clone();
        assertEquals(105, vh4.version);
        assertEquals(100, vh4.getVersion());
        compareWithBitSet(bs1, vh4);
        
        // use vh1 to overwrite vh2
        vh1.version = 105;
        vh1.addException(100,106);
        assertTrue(vh2.sameAs(vh1));
        compareWithBitSet(bs2, vh2);
        validateExceptions(vh2);
      }
    } finally {
      RVVException.UseTreeSetsForTesting = false;
    }
  }
  
  /**
   * Construct a region version holder that matches the seen revisions
   * passed in the bit set.
   * 
   */
  private RegionVersionHolder buildHolder(BitSet bs) {
    
    //Createa version holder
    RegionVersionHolder vh = new RegionVersionHolder(member);
    
    //Record all of the version in the holder
    recordVersions(vh, bs);
    
    //Make sure the holder looks matches the bitset.
    compareWithBitSet(bs, vh);
    
    return vh;
  }

  /**
   * Record all the versions represented in the bit set in the 
   * RegionVersionHolder. This method is overridden in subclasses
   * to change the recording order.
   */
  protected void recordVersions(RegionVersionHolder vh, BitSet bs) {
//    System.out.println("vh="+vh);
    for(int i =1; i < bs.length(); i++) {
      if(bs.get(i)) {
        vh.recordVersion(i, null);
//        System.out.println("after adding " + i + ", vh="+vh);
      }
    }
  }
  
  /**
   * Test a case in 46522 where the received exceptions end up 
   * not being in the RVV interval.
   */
  public void testConsumeReceivedRevisions() {
    testConsumeReceivedRevisions(false);
    testConsumeReceivedRevisions(true);
  }
  
  private void testConsumeReceivedRevisions(boolean useTreeSets) {
    try {
      RVVException.UseTreeSetsForTesting = useTreeSets;
      //Create a bit set which matches the seen versions of vh1
      BitSet bs1 = new BitSet();
      bs1.set(1, 6);
      bs1.set(27, 29);
      bs1.set(30, 41);
      bs1.set(42, 43);
      bs1.set(47, 49);
      bs1.set(50, 101);
      
      RegionVersionHolder vh1 = buildHolder(bs1);
      validateExceptions(vh1);
      compareWithBitSet(bs1, vh1);
  
      {
        //Initialize a vector that has seen some later versions, and has some older
        //exceptions
        BitSet bs2 = new BitSet();
        
        //Add an exception that doesn't overlap with vh1 exceptions
        bs2.set(1, 6);
        bs2.set(20, 44);
        bs2.set(49, 101);
        
        RegionVersionHolder vh2 = buildHolder(bs2);
        validateExceptions(vh2);
        vh2.initializeFrom(vh1);
  //      bs2.or(bs1);
        assertEquals(100, vh2.version);
        compareWithBitSet(bs1, vh2);
        validateExceptions(vh2);
      }
    } finally {
      RVVException.UseTreeSetsForTesting = false;
    }
  }
  
  public void testChangeSetForm() {
    try {
      RVVException.UseTreeSetsForTesting = true;
      //Create a bit set which matches the seen versions of vh1
      BitSet bs1 = new BitSet();
      bs1.set(1024);
      
      RegionVersionHolder vh1 = buildHolder(bs1);
      bs1.set(510);
      bs1.set(511);
      bs1.set(512);
      recordVersions(vh1, bs1);
      validateExceptions(vh1);
      compareWithBitSet(bs1, vh1);
  
    } finally {
      RVVException.UseTreeSetsForTesting = false;
    }
  }

  private void compareWithBitSet(BitSet bitSet,
      RegionVersionHolder versionHolder) {
    for(int i = 1; i < bitSet.length(); i++) {
      assertEquals("For entry " + i + " version=" + versionHolder, bitSet.get(i), versionHolder.contains(i));
    }
  }
  
  /**
   * Test the dominates relation between RegionVersionHolders.
   * 
   * This test currently fails in the last case with two different
   * holders with the same exception list expressed differently.
   * Hence it is disabled.
   * 
   * See bug 47106
   */
  public void testDominates() {
    testDominates(false);
    testDominates(true);
  }
  
  
  private void testDominates(boolean useTreeSets) {
    try {
      RVVException.UseTreeSetsForTesting = useTreeSets;
      
      //Simple case, - vh2 has a greater version than vh1
      {
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
  
        vh1.recordVersion(100, null);
        BitSet bs1 = new BitSet();
        bs1.set(1, 100);
        recordVersions(vh1, bs1);
        
        BitSet bs2 = new BitSet();
        bs2.set(1, 105);
        recordVersions(vh2, bs2);
        assertFalse(vh1.dominates(vh2));
        assertTrue(vh2.dominates(vh1));
      }
  
      // test with a gap between the bitsetVersion and the version
      {
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
  
        BitSet bs1 = new BitSet();
        bs1.set(1, 101);
        recordVersions(vh1, bs1);
        // this test assumes that the width is much greater than 100, so use the
        // original RegionVersionHolder.BIT_SET_WIDTH in order for the assertions to be correct
        vh1.recordVersion(2*originalBitSetWidth, null);
        
        BitSet bs2 = new BitSet();
        bs2.set(1, 2*originalBitSetWidth+1);
        recordVersions(vh2, bs2);
        
        //vh1={rv2048 bsv100 bs={0, 1948}} or {rv2048 bsv2048 bs={0}; [e(n=2048 p=100)]} after merging 
        //vh2={rv2048 bsv2047 bs={0, 1}}   or {rv2048 bsv2048 bs={0}} after merging
        
        assertFalse(vh1.dominates(vh2));
        assertTrue(vh2.dominates(vh1));
      }
  
      //More complicated. vh2 has a higher version, but has
      //some exceptions that vh1 does not have.
      {
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
        BitSet bs1 = new BitSet();
        bs1.set(1, 21);
        bs1.set(30, 101);
        recordVersions(vh1,bs1);
        
        BitSet bs2 = new BitSet();
        bs2.set(1, 50);
        bs2.set(60, 105);
        recordVersions(vh2, bs2);
  
        //double check that we didn't screw up the bit sets
        assertFalse(dominates(bs2, bs1));
        assertFalse(dominates(bs1, bs2));
        
        assertFalse(vh1.dominates(vh2));
        assertFalse(vh2.dominates(vh1));
      }
      
      //Same version, but both have some exceptions the other doesn't
      {
        //VH2 will have exceptions for 20-25, 26-30, 41-43
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        BitSet bs1 = new BitSet();
        
        //record some versions to generate two exceptions
        bs1.set(1, 21);
        bs1.set(30, 41);
        bs1.set(43, 101);
        recordVersions(vh1, bs1);
        
        //now populate some older versions.
        bs1.set(25);
        bs1.set(26);
        recordVersions(vh1, bs1);
        
        //VH2 will have exceptions for 20-25, 26-30, 42-45
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
        BitSet bs2 = new BitSet();
        bs2.set(1, 21);
        bs2.set(25, 27);
        bs2.set(30, 43);
        bs2.set(45, 101);
        recordVersions(vh2, bs2);
        assertFalse(vh1.dominates(vh2));
        assertFalse(vh2.dominates(vh1));
      }
      
      //Same version, but vh2 has an exception that vh1 doesn't
      {
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        BitSet bs1 = new BitSet();
        bs1.set(1, 101);
        recordVersions(vh1, bs1);
        
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
        BitSet bs2  = new BitSet();
        bs2.set(1,43);
        bs2.set(45,101);
        recordVersions(vh2, bs2);
        
        assertTrue(vh1.dominates(vh2));
        assertFalse(vh2.dominates(vh1));
      }
      
      //Same version, but vh2 has an exception that vh1 doesn't
      //With overlapping exception 
      {
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        BitSet bs1 = new BitSet();
        bs1.set(1, 44);
        bs1.set(45, 101);
        recordVersions(vh1, bs1);
        
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
        BitSet bs2 = new BitSet();
        bs2.set(1, 40);
        bs2.set(45, 101);
        recordVersions(vh1, bs1);
        assertTrue(vh1.dominates(vh2));
        assertFalse(vh2.dominates(vh1));
      }
      
      //Same version, but vh2 has an exception that vh1 doesn't
      //With some differently expressed
      //but equivalent exceptions.
      {
        RegionVersionHolder vh1 = new RegionVersionHolder(member);
        BitSet bs1 = new BitSet();
        bs1.set(1, 20);
        bs1.set(30, 101);
        recordVersions(vh1, bs1);
        
        bs1.set(25);
        bs1.set(26);
        recordVersions(vh1, bs1);
        
        RegionVersionHolder vh2 = new RegionVersionHolder(member);
        BitSet bs2 = new BitSet();
        bs2.set(1, 20);
        bs2.set(30, 101);
        bs2.set(25);
        bs2.set(26);
        recordVersions(vh2, bs2);
  //      vh1.sameAs(vh2); vh2.sameAs(vh1);  //force bitsets to be merged
        System.out.println("vh1="+vh1);
        System.out.println("vh2="+vh2);
        assertTrue(vh1.dominates(vh2));
        assertTrue(vh2.dominates(vh1));
      }
    } finally {
      RVVException.UseTreeSetsForTesting = false;
    }
    
  }

  /**
   * Return true if bs1 dominates bs2 - meaning that at least all of the bits
   * set in bs2 are set in bs1.
   * @param bs1
   * @param bs2
   * @return
   */
  private boolean dominates(BitSet bs1, BitSet bs2) {
    //bs1 dominates bs2 if it has set at least all of the bits in bs1.
    BitSet copy = new BitSet();
    //Make copy a copy of bit set 2
    copy.or(bs2);
    
    //Clear all of the bits in copy that are set in bit set 1
    copy.andNot(bs1);
    
    //If all of the bits have been cleared in copy, that means
    //bit set 1 had at least all of the bits set that were set in
    //bs2
    return copy.isEmpty();
    
  }
  /**
   * For test purposes, make sure this version holder is internally consistent.
   */
  private void validateExceptions(RegionVersionHolder<?> holder) {
    if (holder.getExceptionForTest() != null) {
      for(RVVException ex : holder.getExceptionForTest()) {
        // now it allows the special exception whose nextVersion==holder.version+1
        if(ex.nextVersion > holder.version+1) {
          Assert.assertTrue(false, "next version too large next=" + ex.nextVersion + " holder version " + holder.version);
        }
        
        if(ex.nextVersion <= ex.previousVersion) {
          Assert.assertTrue(false, "bad next and previous next=" + ex.nextVersion + ", previous=" + ex.previousVersion);
        }
        
        for(ReceivedVersionsIterator it = ex.receivedVersionsIterator(); it.hasNext(); ) {
          Long received = it.next();
          if(received >= ex.nextVersion) {
            Assert.assertTrue(false, "received greater than next next=" + ex.nextVersion + ", received=" + received + " exception=" + ex);
          }
          
          if(received <= ex.previousVersion) {
            Assert.assertTrue(false, "received less than previous prev=" + ex.previousVersion + ", received=" + received);
          }
        }
        if(ex.nextVersion - ex.previousVersion > 1000000) {
          Assert.assertTrue(false, "to large a gap in exceptions prev=" + ex.previousVersion + ", next=" + ex.nextVersion);
        }
      }
    }
  }

}
