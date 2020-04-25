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
package com.pivotal.gemfirexd.internal.engine.map;

import com.google.common.hash.Hashing;

/**
 * Tries to compress key with base64 encoding assuming ascii characters only,
 * in other words, assuming [a-z][A-Z][0-9] and [.*,- ] special characters only.
 * <p/>
 * note: one cannot have escape character '\' as well. only dot, asterik,
 * hyphen, comma and space characters are allowed. Other characters appear as
 * space and won't match different impls of {@code getHashCode} and {@code equals} methods.
 */
class DHMBase64Serializer implements DenseHashMapSerializer<String> {

  private static final boolean DEBUGDEBUG = false;

  private static final int VALUE_WIDTH = 1;
  private static final int MAX_VALUE;

  static {
    int max = 0;
    for (int i = 0; i < VALUE_WIDTH; i++)
      max |= (1 << VALUE_WIDTH * 8);
    MAX_VALUE = max - 1;
  }

  //FNV-1a hash
  private final long addToHash(char c, long hash) {
    return (int)((hash ^ (int)c) * 16777619);
  }

  protected long baseHashValue() {
    return 2166136261L;
  }

  @Override
  public boolean equals(String key, byte[] entry) {

    for (int i = 0, j = 0, len = key.length(); j < len; i += 3) {

      int val = ((entry[i] & 0xff) >>> 2) & 0x00ff;
      char c = decodedChar(val); // 6 bits
      char t = key.charAt(j++);
      if (t != c) {
        return false;
      }

      if (i + 1 >= len) break;

      val = (((entry[i] & 0xff) << 6) & 0x00ff) >>> 2;
      val = (byte)(val | (byte)((entry[i + 1] & 0xff) >> 4)); // 2 bits + 4 bits
      c = decodedChar(val);
      t = key.charAt(j++);
      if (t != c) {
        return false;
      }

      if (i + 2 >= len) break;

      val = (((entry[i + 1] & 0xff) << 4) & 0x00ff) >>> 2;
      val = (byte)(val | ((entry[i + 2] & 0xff) >>> 6) & 0x00ff); // 4 bits + 2 bits
      c = decodedChar(val);
      t = key.charAt(j++);
      if (t != c) {
        return false;
      }

      val = (((entry[i + 2] & 0xff) << 2) & 0x00ff) >> 2; // 6 bits
      c = decodedChar(val);
      t = key.charAt(j++);
      if (t != c) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean equals(byte[] entry1, byte[] entry2) {

    if (entry1.length - VALUE_WIDTH != entry2.length - VALUE_WIDTH) {
      return false;
    }

    for (int i = 0, len = entry1.length; i < len; i++) {
      if (entry1[i] != entry2[i]) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String getKey(byte[] entry) {
    int len = entry.length - VALUE_WIDTH;
    final int totalChars = (int)Math.ceil((8d * len) / 6);
    char[] v = new char[totalChars];
    int j = 0;

    for (int i = 0; i < len; i += 3) {

      int val = ((entry[i] & 0xff) >>> 2) & 0x00ff;
      char c = decodedChar(val); // 6 bits

      v[j++] = c;


      if (i + 1 >= len) break;

      val = (((entry[i] & 0xff) << 6) & 0x00ff) >>> 2;
      val = (byte)(val | (byte)((entry[i + 1] & 0xff) >> 4)); // 2 bits + 4 bits
      c = decodedChar(val);

      v[j++] = c;

      if (i + 2 >= len) break;

      val = (((entry[i + 1] & 0xff) << 4) & 0x00ff) >>> 2;
      val = (byte)(val | ((entry[i + 2] & 0xff) >>> 6) & 0x00ff); // 4 bits + 2 bits
      c = decodedChar(val);

      v[j++] = c;

      val = (((entry[i + 2] & 0xff) << 2) & 0x00ff) >> 2;
      c = decodedChar(val);

      v[j++] = c;
    }

    v[j++] = '\n';

    return new String(v);
  }

  @Override
  public int deserializeValue(byte[] entry) {
    return MAX_VALUE & DenseIntValueHashMap.readInt(entry, entry.length - VALUE_WIDTH, VALUE_WIDTH);
  }

  @Override
  public int getHashCode(String key) {

    final int keyLen = key.length();

    long hash = baseHashValue();
    for (int i = 0; i < keyLen; i++) {
      hash = addToHash(key.charAt(i), hash);
    }

    //return Integer.rotateLeft((int)hash, 3);
    return Hashing.murmur3_32().hashInt((int)hash).asInt();
  }

  @Override
  public int getHashCode(byte[] entry) {
    int len = entry.length - VALUE_WIDTH;
    long hash = baseHashValue();

    for (int i = 0; i < len; i += 3) {

      int val = ((entry[i] & 0xff) >>> 2) & 0x00ff;
      char c = decodedChar(val); // 6 bits

      hash = addToHash(c, hash);


      if (i + 1 >= len) break;

      val = (((entry[i] & 0xff) << 6) & 0x00ff) >>> 2;
      val = (byte)(val | (byte)((entry[i + 1] & 0xff) >> 4)); // 2 bits + 4 bits
      c = decodedChar(val);

      hash = addToHash(c, hash);

      if (i + 2 >= len) break;

      val = (((entry[i + 1] & 0xff) << 4) & 0x00ff) >>> 2;
      val = (byte)(val | ((entry[i + 2] & 0xff) >>> 6) & 0x00ff); // 4 bits + 2 bits
      c = decodedChar(val);

      hash = addToHash(c, hash);

      val = (((entry[i + 2] & 0xff) << 2) & 0x00ff) >> 2;
      c = decodedChar(val);

      hash = addToHash(c, hash);
    }

    //return Integer.rotateLeft((int)hash, 3);
    return Hashing.murmur3_32().hashInt((int)hash).asInt();
  }

  @Override
  public byte[] serialize(String key, int value) {

    final int keyLen = key.length();

    final int roundOfflen = (int)Math.ceil((6d * keyLen) / 8);

    final byte[] tgtVal;
    int beginWrite;

    tgtVal = new byte[roundOfflen + VALUE_WIDTH];
    beginWrite = 0;

    for (int i = 0; i < keyLen; i += 4) {

      int c = encodedIntVal(key.charAt(i));
      writeToHigherNibble(tgtVal, beginWrite, (byte)c, 6); //byte-1

      if (i + 1 >= keyLen) break;

      c = encodedIntVal(key.charAt(i + 1));
      writeToLowerNibble(tgtVal, beginWrite, (byte)c, 2); //byte-1

      if (DEBUGDEBUG) {
        String s1 = binString(tgtVal[beginWrite]);
        System.out.println(s1);
        String bit = s1.substring(0, 6);
        int v1 = Integer.parseInt(bit, 2);
        char c1 = decodedChar(v1);
        assert c1 == key.charAt(i);
      }

      beginWrite++;
      writeToHigherNibble(tgtVal, beginWrite, (byte)c, 4); //byte-2

      if (i + 2 >= keyLen) break;

      c = encodedIntVal(key.charAt(i + 2));
      writeToLowerNibble(tgtVal, beginWrite, (byte)c, 4); //byte-2

      if (DEBUGDEBUG) {
        String s1 = binString(tgtVal[beginWrite - 1]);
        String s2 = binString(tgtVal[beginWrite]);
        System.out.println(s2);
        String bit = s1.substring(6, 8) + s2.substring(0, 4);
        int v2 = Integer.parseInt(bit, 2);
        char c2 = decodedChar(v2);
        assert c2 == key.charAt(i + 1);
      }

      beginWrite++;
      writeToHigherNibble(tgtVal, beginWrite, (byte)c, 2); //byte-3

      if (i + 3 >= keyLen) break;

      c = encodedIntVal(key.charAt(i + 3));
      writeToLowerNibble(tgtVal, beginWrite, (byte)c, 6); //byte-3

      if (DEBUGDEBUG) {
        String s2 = binString(tgtVal[beginWrite - 1]);
        String s3 = binString(tgtVal[beginWrite]);
        System.out.println(s3);
        int v3 = Integer.parseInt(s2.substring(4, 8) + s3.substring(0, 2), 2);
        char c3 = decodedChar(v3);
        assert c3 == key.charAt(i + 2);

        int v4 = Integer.parseInt(s3.substring(2, 8), 2);
        char c4 = decodedChar(v4);
        assert c4 == key.charAt(i + 3);

        System.out.println();
      }

      beginWrite++;

    }

    assert value <= MAX_VALUE : value + " " + MAX_VALUE;
    assert beginWrite + VALUE_WIDTH == tgtVal.length;

    assert equals(key, tgtVal);

    DenseIntValueHashMap.writeInt(tgtVal, value, beginWrite, VALUE_WIDTH);

    return tgtVal;
  }

  private String binString(byte b) {
    String s = Integer.toBinaryString(b);
    if (s.length() < 8) {
      int pad = 8 - s.length();
      for (int i = 0; i < pad; i++) {
        s = "0" + s;
      }
    }
    return s.substring(s.length() - 8, s.length());
  }

  private static final void writeToHigherNibble(final byte[] encode, final int offset, final byte c, final int numBits) {
    encode[offset] = (byte)(c << (8 - numBits));
  }

  private static final void writeToLowerNibble(final byte[] encode, final int offset, final byte c, final int numBits) {
    encode[offset] = (byte)(encode[offset] | (byte)(c >>> (6 - numBits)));
  }

  private static final int encodedIntVal(final char ch) {
    int chaVal = 0;
    // @formatter:off
    switch(ch){
      case' ':chaVal=0;break; case'a':chaVal=1;break;
      case'b':chaVal=2;break; case'c':chaVal=3;break;
      case'd':chaVal=4;break; case'e':chaVal=5;break;
      case'f':chaVal=6;break; case'g':chaVal=7;break;
      case'h':chaVal=8;break; case'i':chaVal=9;break;
      case'j':chaVal=10;break; case'k':chaVal=11;break;
      case'l':chaVal=12;break; case'm':chaVal=13;break;
      case'n':chaVal=14;break; case'o':chaVal=15;break;
      case'p':chaVal=16;break; case'q':chaVal=17;break;
      case'r':chaVal=18;break; case's':chaVal=19;break;
      case't':chaVal=20;break; case'u':chaVal=21;break;
      case'v':chaVal=22;break; case'w':chaVal=23;break;
      case'x':chaVal=24;break; case'y':chaVal=25;break;
      case'z':chaVal=26;break; case'.':chaVal=27;break;
      case'*':chaVal=28;break; case',':chaVal=29;break;
      case'-':chaVal=30;break; case'2':chaVal=31;break;
      case'A':chaVal=32;break; case'B':chaVal=33;break;
      case'C':chaVal=34;break; case'D':chaVal=35;break;
      case'E':chaVal=36;break; case'F':chaVal=37;break;
      case'G':chaVal=38;break; case'H':chaVal=39;break;
      case'I':chaVal=40;break; case'J':chaVal=41;break;
      case'K':chaVal=42;break; case'L':chaVal=43;break;
      case'M':chaVal=44;break; case'N':chaVal=45;break;
      case'O':chaVal=46;break; case'P':chaVal=47;break;
      case'Q':chaVal=48;break; case'R':chaVal=49;break;
      case'S':chaVal=50;break; case'T':chaVal=51;break;
      case'U':chaVal=52;break; case'V':chaVal=53;break;
      case'W':chaVal=54;break; case'0':chaVal=55;break;
      case'1':chaVal=56;break; case'3':chaVal=57;break;
      case'4':chaVal=58;break;case'5':chaVal=59;break;
      case'6':chaVal=60;break;case'7':chaVal=61;break;
      case'8':chaVal=62;break;case'9':chaVal=63;break;
      default:chaVal=0;
    }
    // @formatter:on
    return chaVal;
  }

  char decodedChar(int val) {
    char ch = ' ';
    // @formatter:off
    switch(val){
      case 0:ch=' ';break; case 1:ch='a';break;
      case 2:ch='b';break; case 3 :ch='c';break;
      case 4:ch='d';break; case 5 :ch='e';break;
      case 6:ch='f';break; case 7 :ch='g';break;
      case 8:ch='h';break; case 9 :ch='i';break;
      case 10:ch='j';break; case 11:ch='k';break;
      case 12:ch='l';break; case 13:ch='m';break;
      case 14:ch='n';break; case 15:ch='o';break;
      case 16:ch='p';break; case 17:ch='q';break;
      case 18:ch='r';break; case 19:ch='s';break;
      case 20:ch='t';break; case 21:ch='u';break;
      case 22:ch='v';break; case 23:ch='w';break;
      case 24:ch='x';break; case 25:ch='y';break;
      case 26:ch='z';break; case 27:ch='.';break;
      case 28:ch='*';break; case 29:ch=',';break;
      case 30:ch='-';break; case 31 :ch='2';break;
      case 32:ch='A';break; case 33:ch='B';break;
      case 34:ch='C';break; case 35:ch='D';break;
      case 36:ch='E';break; case 37:ch='F';break;
      case 38:ch='G';break; case 39:ch='H';break;
      case 40:ch='I';break; case 41:ch='J';break;
      case 42:ch='K';break; case 43:ch='L';break;
      case 44:ch='M';break; case 45:ch='N';break;
      case 46:ch='O';break; case 47:ch='P';break;
      case 48:ch='Q';break; case 49:ch='R';break;
      case 50:ch='S';break; case 51:ch='T';break;
      case 52:ch='U';break; case 53:ch='V';break;
      case 54:ch='W';break; case 55:ch='0';break;
      case 56:ch='1';break; case 57:ch='3';break;
      case 58:ch='4';break; case 59:ch='5';break;
      case 60:ch='6';break; case 61:ch='7';break;
      case 62:ch='8';break; case 63:ch='9';break;
      default:ch=' ';
    }
    // @formatter:on
    return ch;
  }
}

