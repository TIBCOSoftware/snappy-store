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
//
//  RowFormatterTest.java
//  gemfire
//
//  Created by Eric Zoerner on 2009-05-05.

package com.pivotal.gemfirexd.internal.engine.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.catalog.types.DefaultInfoImpl;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDate;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDouble;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.SQLLongint;
import com.pivotal.gemfirexd.internal.iapi.types.SQLReal;
import com.pivotal.gemfirexd.internal.iapi.types.SQLSmallint;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTime;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTimestamp;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTinyint;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

@SuppressWarnings("unchecked")
public class RowFormatterTest extends JdbcTestBase {
  
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(RowFormatterTest.class));
  }

  public static final class NullHolder implements ResultWasNull {

    boolean wasNull;

    public void setWasNull() {
      this.wasNull = true;
    }

    public boolean wasNull() {
      return this.wasNull;
    }
  }

  public RowFormatterTest(String name) {
    super(name);
  }

  public void testReadAndWriteInts() {
    byte[] bytes = new byte[256];
    int numToTest = 1024;
    Random rand = new Random();
    // make sure we test at least one negative int
    boolean testedNeg = false;
    for (int i = 0; i < numToTest || !testedNeg; i++) {
      int nextInt = rand.nextInt();
      if (nextInt < 0) testedNeg = true;
      assertEquals(4, RowFormatter.writeInt(bytes, nextInt, 42));
      assertEquals(nextInt, RowFormatter.readInt(bytes, 42));      
    }
  }

  public void testReadAndWriteCompactInts() throws IOException {
    byte[] bytes = new byte[256];
    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dout = new DataOutputStream(bos);
    final int numToTest = 1024;
    TIntArrayList allInts = new TIntArrayList(numToTest);
    Random rand = new Random();
    // make sure we test at least one negative int
    boolean testedNeg = false;
    for (int i = 0; i < numToTest || !testedNeg; i++) {
      int nextInt = rand.nextInt();
      if (nextInt < 0) {
        testedNeg = true;
      }
      assertTrue(RowFormatter.writeCompactInt(bytes, nextInt, 42) < 47);
      InternalDataSerializer.writeSignedVL(nextInt, dout);
      assertTrue(RowFormatter.getCompactIntNumBytes(nextInt) <= 5);
      allInts.add(nextInt);
      assertEquals(nextInt, RowFormatter.readCompactInt(bytes, 42));
    }
    // check for some border cases everytime
    int[] intsToTest = new int[] { 0x40, 0x3f, 0x7f, 0x80, 0x78, 0xffffff40,
        0xffffff80, 0xffffffc0, 0xffffff13 };
    for (int intToTest : intsToTest) {
      assertTrue(RowFormatter.writeCompactInt(bytes, intToTest, 42) < 44);
      InternalDataSerializer.writeSignedVL(intToTest, dout);
      assertTrue(RowFormatter.getCompactIntNumBytes(intToTest) <= 2);
      allInts.add(intToTest);
      assertEquals(intToTest, RowFormatter.readCompactInt(bytes, 42));
    }
    intsToTest = new int[] { 0x6fff, 0x7fff, 0x8fff, 0x8000, 0x4000, 0xa000,
        0xb000, 0xc000, 0x1fffff, 0x3fffff, 0x5fffff, 0x7fffff, 0x8fffff,
        0x800000, 0x400000, 0xa00000, 0xb00000, 0xc00000, 0xfffffff,
        0x1fffffff, 0x5fffffff, 0x7fffffff, 0x8fffffff, 0xffffffff, 0x4000000,
        0x8000000, 0x40000000, 0x80000000, 0xa0000000, 0xb0000000, 0xc0000000,
        0xf0000000, 0xffff8000, 0xffff4000, 0xffff2000, 0xfffff000, 0xfffffe00,
        0xffefffff, 0xffe00000, 0xff100000, 0xfef10000, 0xff800000, 0xff600000,
        0xf1000000, 0xfe000000, 0xf3000000, 0x81000000, 0xab000000 };
    for (int intToTest : intsToTest) {
      assertTrue(RowFormatter.writeCompactInt(bytes, intToTest, 42) < 47);
      InternalDataSerializer.writeSignedVL(intToTest, dout);
      assertTrue(RowFormatter.getCompactIntNumBytes(intToTest) <= 5);
      allInts.add(intToTest);
      assertEquals(intToTest, RowFormatter.readCompactInt(bytes, 42));
    }

    // now check for deserialization using InternalDataSerializer.readCompactInt
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(
        bos.toByteArray()));
    for (int i = 0; i < allInts.size(); i++) {
      assertEquals(allInts.get(i),
          (int)InternalDataSerializer.readSignedVL(in));
    }
  }

  public void testReadAndWriteLongs() throws IOException {
    byte[] bytes = new byte[256];
    int numToTest = 1024;
    Random rand = new Random();
    // make sure we test at least one negative long
    boolean testedNeg = false;
    for (int i = 0; i < numToTest || !testedNeg; i++) {
      long nextLong = rand.nextLong();
      if (nextLong < 0) testedNeg = true;
      assertEquals(8, RowFormatter.writeLong(bytes, nextLong, 42));
      assertEquals(nextLong, RowFormatter.readLong(bytes, 42));      
    }
  }

  public void testReadAndWriteSmallInts() {
    byte[] bytes = new byte[256];
    int numToTest = 1024;
    Random rand = new Random();
    // make sure we test at least one negative short
    boolean testedNeg = false;
    for (int i = 0; i < numToTest || !testedNeg; i++) {
      short nextShort = (short)rand.nextInt();
      if (nextShort < 0) testedNeg = true;
      assertEquals(2, new SQLSmallint(nextShort).writeBytes(bytes, 42, null));
      SQLSmallint newSmallInt = new SQLSmallint();
      assertEquals(2, newSmallInt.readBytes(bytes, 42, 2));
      assertEquals(nextShort, ((Integer)newSmallInt.getObject()).intValue());      
    }
  }

  public void testBug49310() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))  partition by list (tid) "
        + "(VALUES (14, 1, 2, 10, 4, 5), VALUES (6, 7, 16, 9, 3, 11), "
        + "VALUES (12, 13, 0, 15, 8, 17))");
    stmt.execute("create index idx_1 on trade.customers(cust_name)");

    final byte[] bytes1 = new byte[] { 2, 0, 0, 65, 8, 111, 32, 99, 7, -42, 6,
        25, 97, 100, 100, 114, 101, 115, 115, 32, 105, 115, 32, 111, 32, 99, 0,
        0, 0, 17, 5, 0, 8, 0, 12, 0, 26, 0 };
    final byte[] bytes2 = new byte[] { 2, 0, 0, 59, -68, 111, 7, -48, 4, 3,
        121, 97, 116, 101, 109, 121, 113, 97, 106, 106, 102, 122, 32, 109, 107,
        114, 122, 105, 107, 121, 98, 97, 32, 108, 112, 98, 121, 101, 32, 32,
        105, 110, 115, 32, 120, 108, 102, 108, 116, 101, 120, 118, 115, 101,
        118, 98, 115, 106, 114, 121, 97, 119, 106, 0, 0, 0, 12, 5, 0, 6, 0, 10,
        0, 63, 0 };

    RowFormatter rf = ((GemFireContainer)Misc.getRegion("/TRADE/CUSTOMERS",
        true, false).getUserAttribute()).getCurrentRowFormatter();
    DataValueDescriptor[] dvds1 = new DataValueDescriptor[rf.getNumColumns()];
    rf.getColumns(bytes1, dvds1, null);
    assertEquals("o c", dvds1[1].toString());

    DataValueDescriptor[] dvds2 = new DataValueDescriptor[rf.getNumColumns()];
    rf.getColumns(bytes2, dvds2, null);
    assertEquals("o", dvds2[1].toString());

    // compare DVDs
    assertTrue(dvds1[1].compare(dvds2[1]) > 0);
    assertTrue(dvds2[1].compare(dvds1[1]) < 0);

    // then the serialized forms
    long off1 = rf.getOffsetAndWidth(2, bytes1);
    int columnWidth1 = (int)(off1 & 0xFFFFFFFF);
    int offset1 = (int)(off1 >>> Integer.SIZE);

    long off2 = rf.getOffsetAndWidth(2, bytes2);
    int columnWidth2 = (int)(off2 & 0xFFFFFFFF);
    int offset2 = (int)(off2 >>> Integer.SIZE);

    assertTrue(SQLChar.compareString(bytes1, offset1, columnWidth1, bytes2,
        offset2, columnWidth2) > 0);
    assertTrue(SQLChar.compareString(bytes2, offset2, columnWidth2, bytes1,
        offset1, columnWidth1) < 0);
  }

  public void testGenerateRowWithNotNullableIntegers()
      throws StandardException, IOException {

    int hiValue = 10;
    int loValue = -10;

    int numFields = (hiValue - loValue) / 2 + 1;
    int numBytes = numFields * 4 + 1 /* for version */;
    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    final DataOutputStream dos = new DataOutputStream(bos);
    InternalDataSerializer.writeSignedVL(1, dos); // for version

    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    int index = 0;
    for (int v = -10; v <= 10; v +=2, index++) {
      dvds[index] = new SQLInteger(v);
      // fill in expected bytes
      dos.writeInt(v);
      /*
      int shiftedV = v;
      for (int bi = 0; bi < 4; bi++) {
        expectedBytes[(index * 4) + bi] = (byte)shiftedV;
        shiftedV >>>= 8;
      }
      */
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
        = new ColumnDescriptor("c" + p,
                               p,
                               DataTypeDescriptor.INTEGER_NOT_NULL,
                               null, // default
                               null, // defaultInfo
                               (UUID)null, // table uuid
                               (UUID)null, // default uuid
                               0L, // autoincStart
                               0L, // autoincInc
                               0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    // verify bytes is correct
    final byte[] expectedBytes = bos.toByteArray();
    assertNotNull(bytes);
    assertEquals(numBytes, bytes.length);
    assertTrue(Arrays.equals(expectedBytes, bytes));
  }

  public void testGetNotNullableIntegers()
  throws StandardException {
    
    int hiValue = 10;
    int loValue = -10;
    int increment = 2;
    
    int numFields = (hiValue - loValue) / increment + 1;
    
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    int index = 0;
    for (int v = -10; v <= 10; v +=2, index++) {
      dvds[index] = new SQLInteger(v);
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             DataTypeDescriptor.INTEGER_NOT_NULL,
                             new SQLInteger(0), // default
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify int values
    index = 0;
    for (int v = -10; v <= 10; v += 2, index++) {
      assertEquals("for index=" + index, v, outDvds[index].getObject());
    }
  }

  public void testGetNullableIntegersNoNulls()
  throws StandardException {
    
    int hiValue = 10;
    int loValue = -10;
    int increment = 2;
    
    int numFields = (hiValue - loValue) / increment + 1;
    
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    int index = 0;
    for (int v = -10; v <= 10; v +=2, index++) {
      dvds[index] = new SQLInteger(v);
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             DataTypeDescriptor.INTEGER,
                             new SQLInteger(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify int values
    index = 0;
    for (int v = -10; v <= 10; v += 2, index++) {
      assertEquals("for index=" + index, v, outDvds[index].getObject());
    }
  }

  public void testFixedWidthCharsWithPadding()
  throws StandardException {
        
    int numFields = 10;
    
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    String[] strings = new String[numFields];
    for (int i = 0; i < numFields; i++) {
      char[] cha = new char[i];
      Arrays.fill(cha, (char)('A' + i));
      strings[i] = new String(cha);
      dvds[i] = new SQLChar(new String(strings[i]));
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    int expectedBytesLength = 1; // for version
    for (int p = 1; p <= dvds.length; p++) {
      DataTypeDescriptor dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
          Types.CHAR, false, // not nullable
          // includes 5 extra characters of padding for test purposes
          p + 4);
      expectedBytesLength += p - 1; // now one byte per char + offset bytes below
      ColumnDescriptor cd =
          new ColumnDescriptor("c" + p,
                               p,
                               dtd,
                               new SQLChar(), // default ("")
                               null, // defaultInfo
                               (UUID) null, // table uuid
                               (UUID) null, // default uuid
                               0L, // autoincStart
                               0L, // autoincInc
                               0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    int numOffsetBytes = rf.getNumOffsetBytes();
    expectedBytesLength += numOffsetBytes * dvds.length;
    byte[] bytes = rf.generateBytes(dvds);

    // test to make sure bytes is expected length for fixed-width chars
    assertEquals(expectedBytesLength, bytes.length);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify String values
    char[] paddingChars = new char[5];
    Arrays.fill(paddingChars, ' ');
    //String padding = new String(paddingChars);
    for (int i = 0; i < numFields; i++) {
      String expectedString = strings[i];// + padding;
      assertEquals("for index=" + i, expectedString, outDvds[i].getObject());
    }
  }

  public void testNullableCharsWithPadding()
  throws StandardException {
        
    int numFields = 10;
    
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    String[] strings = new String[numFields];
    for (int i = 0; i < numFields; i++) {
      char[] cha = new char[i];
      Arrays.fill(cha, (char)('A' + i));
      strings[i] = new String(cha);
      dvds[i] = new SQLChar(new String(strings[i]));
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    int expectedBytesLength = 1; // for version
    for (int p = 1; p <= dvds.length; p++) {
      DataTypeDescriptor dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
          Types.CHAR, true, // is nullable
          // includes 5 extra characters of padding for test purposes
          p + 4);
      expectedBytesLength += p - 1; // now one byte per char + offset bytes below
      ColumnDescriptor cd =
          new ColumnDescriptor("c" + p,
                               p,
                               dtd,
                               new SQLChar(), // default ("")
                               null, // defaultInfo
                               (UUID) null, // table uuid
                               (UUID) null, // default uuid
                               0L, // autoincStart
                               0L, // autoincInc
                               0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    int numOffsetBytes = rf.getNumOffsetBytes();
    expectedBytesLength += numOffsetBytes * dvds.length;

    byte[] bytes = rf.generateBytes(dvds);

    // test to make sure bytes is expected length for fixed-width chars
    assertEquals(expectedBytesLength, bytes.length);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify String values
    char[] paddingChars = new char[5];
    Arrays.fill(paddingChars, ' ');
    //String padding = new String(paddingChars);
    for (int i = 0; i < numFields; i++) {
      String expectedString = strings[i];// + padding;
      assertEquals("for index=" + i, expectedString, outDvds[i].getObject());
    }
  }

  public void testGetVarchars()
  throws StandardException {
        
    int numFields = 10;
    
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    String[] strings = new String[numFields];
    for (int i = 0; i < numFields; i++) {
      char[] cha = new char[i];
      Arrays.fill(cha, (char)('A' + i));
      strings[i] = new String(cha);
      dvds[i] = new SQLVarchar(new String(strings[i]));
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd =
          new ColumnDescriptor("c" + p,
                               p,
                               DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR),
                               new SQLVarchar(), // default ("")
                               null, // defaultInfo
                               (UUID) null, // table uuid
                               (UUID) null, // default uuid
                               0L, // autoincStart
                               0L, // autoincInc
                               0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify String values
    for (int i = 0; i < numFields; i++) {
      assertEquals("for index=" + i, strings[i], outDvds[i].getObject());
    }
  }

  public void testGetNullableIntegersWithNulls()
  throws StandardException {
    // add three nulls in the mix at logical positions 1, 7, and 14
    List<Integer> nullPositions = Arrays.asList(1, 7, 13);
    int hiValue = 10;
    int loValue = -10;
    int increment = 2;
    
    int numFields = (hiValue - loValue) / increment + nullPositions.size() + 1;
    
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[numFields];
    int v = -10;
    for (int index = 0; index < numFields; index++) {
      if (nullPositions.contains(index + 1)) {
        dvds[index] = new SQLInteger(); // null
      } else {
        dvds[index] = new SQLInteger(v);
        v += 2;
      }
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             DataTypeDescriptor.INTEGER,
                             new SQLInteger(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify values
    v = -10;
    for (int index = 0; index < numFields; index++) {
      if (nullPositions.contains(index + 1)) {
        assertEquals("for index=" + index, null, outDvds[index].getObject());
      }
      else {
        assertEquals("for index=" + index, v, outDvds[index].getObject());
        v += 2;
      }
    }
  }

  /**
   * Tests the getAsLong API of RowFormatter, if the column type is having type
   * SQLLong & the value is null;
   * @throws StandardException
   */
  public void testBug41168() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLLongint();      
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             DataTypeDescriptor.INTEGER,
                             new SQLLongint(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertEquals(0, rf.getAsLong(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsDoubleForNullableDoubleWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLDouble();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLDouble(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertEquals(0.0, rf.getAsDouble(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsStringForNullableStringWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLVarchar();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.CHAR,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLChar(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertNull(rf.getAsString(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsByteForNullableByteWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLTinyint();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TINYINT,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLTinyint(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertEquals(0, rf.getAsByte(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsShortForNullableShortWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLSmallint();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.SMALLINT;
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLSmallint(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertEquals(0, rf.getAsShort(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsIntForNullableIntWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLInteger();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.INTEGER;
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLInteger(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertEquals(0, rf.getAsInt(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsFloatForNullableFloatWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLReal();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.FLOAT,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLReal(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    NullHolder wasNull = new NullHolder();
    assertEquals(0.0f, rf.getAsFloat(1, bytes, wasNull));
    assertTrue(wasNull.wasNull);
  }

  public void testGetAsDateForNullableDateWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLDate();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DATE,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLDate(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    assertNull(rf.getAsDate(1, bytes, Calendar.getInstance(), null));
  }

  public void testGetAsTimeStampForNullableTimeStampWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLTimestamp();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIMESTAMP,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLTimestamp(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    assertNull(rf.getAsTimestamp(1, bytes, Calendar.getInstance(), null));
  }

  public void testGetAsTimeForNullableTimeWithNull() throws StandardException {
    DataValueDescriptor[] dvds
      = new DataValueDescriptor[1];    
    for (int index = 0; index < 1; index++) {      
        dvds[index] = new SQLTime();      
    }
    DataTypeDescriptor dtd
    = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIME,true);
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             dtd,
                             new SQLTime(), // default (null)
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);
    assertNull(rf.getAsTime(1, bytes, Calendar.getInstance(), null));
  }

  public void testSetColumnsNotNullableIntegers()
  throws StandardException {
    
    int hiValue = 10;
    int loValue = -10;
    int increment = 2;
    
    int numFields = (hiValue - loValue) / increment + 1;
    
    DataValueDescriptor[] dvds
    = new DataValueDescriptor[numFields];
    int index = 0;
    for (int v = -10; v <= 10; v +=2, index++) {
      dvds[index] = new SQLInteger(v);
    }
    
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    
    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd
      = new ColumnDescriptor("c" + p,
                             p,
                             DataTypeDescriptor.INTEGER_NOT_NULL,
                             new SQLInteger(0), // default
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);

    // set some columns
    // set columns at index 0 and 5 to 42 and -42 respectively
    FormatableBitSet bitSet = new FormatableBitSet(cdl.size());
    bitSet.set(0);
    bitSet.set(5);

    // sparse array of new values to set
    DataValueDescriptor[] newDvds = new DataValueDescriptor[cdl.size()];
    newDvds[0] = new SQLInteger(42);
    newDvds[5] = new SQLInteger(-42);

    bytes = rf.setColumns(bitSet, newDvds, bytes, rf);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify int values
    assertEquals("for index=0", 42, outDvds[0].getObject());
    index = 1;
    for (int v = -8; v <= 10; v += 2, index++) {
      if (index == 5) {
        assertEquals("for index=5", -42, outDvds[5].getObject());
      }
      else {
        assertEquals("for index=" + index, v, outDvds[index].getObject());
      }
    }
  }
  
  public void testGetMixedTypes()
  throws StandardException {
    
    int numFields = 2;
    DataValueDescriptor[] dvds = new DataValueDescriptor[numFields];
    dvds[0] = new SQLInteger(-42);
    dvds[1] = new SQLVarchar("Carpe Diem");
        
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();
    

    ColumnDescriptor cd
      = new ColumnDescriptor("c1",
                              1,
                              DataTypeDescriptor.INTEGER_NOT_NULL,
                              new SQLInteger(0), // default
                              null, // defaultInfo
                              (UUID)null, // table uuid
                              (UUID)null, // default uuid
                              0L, // autoincStart
                              0L, // autoincInc
                              0L,false); // autoincValue
    cdl.add(cd);
    
    DataTypeDescriptor dtd
      = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR,
                                                        true,
                                                        255);    
    cd
      = new ColumnDescriptor("c2",
                             2,
                             dtd,
                             new SQLVarchar(""), // default
                             null, // defaultInfo
                             (UUID)null, // table uuid
                             (UUID)null, // default uuid
                             0L, // autoincStart
                             0L, // autoincInc
                             0L,false); // autoincValue
    cdl.add(cd);
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    assertEquals(2, outDvds.length);

    // verify values
    assertEquals(-42, outDvds[0].getObject());
    assertEquals("Carpe Diem", outDvds[1].getObject());
  }

  /*
   * Test use of One bytes for offset. Using both null and not null null-able
   * Dvds.
   */
  public void test1BytesOffset() throws Exception {
    List<Integer> nullPositions = Arrays.asList(1, 7, 13);
    int hiValue = 10;
    int loValue = -10;
    int increment = 2;

    int numFields = (hiValue - loValue) / increment + nullPositions.size() + 1;

    DataValueDescriptor[] dvds = new DataValueDescriptor[numFields];
    int v = -10;
    for (int index = 0; index < numFields; index++) {
      if (nullPositions.contains(index + 1)) {
        dvds[index] = new SQLInteger(); // null
      } else {
        dvds[index] = new SQLInteger(v);
        v += 2;
      }
    }
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
          DataTypeDescriptor.INTEGER, new SQLInteger(), // default (null)
          null, // defaultInfo
          (UUID) null, // table uuid
          (UUID) null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    // check bytes used for offset.
    assertEquals("Should use a 1 byte offset ", 1, rf.getNumOffsetBytes());

    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify values
    v = -10;
    for (int index = 0; index < numFields; index++) {
      if (nullPositions.contains(index + 1)) {
        assertEquals("for index=" + index, null, outDvds[index].getObject());
      }
      else {
        assertEquals("for index=" + index, v, outDvds[index].getObject());
        v += 2;
      }
    }
  }

  /*
   * Test use of two bytes for offset. Using both null and not null null-able
   * Dvds.
   */
  public void test2BytesOffset() throws Exception {
    List<Integer> nullPositions = Arrays.asList(1, 7, 13);
    int hiValue = 100;
    int loValue = -100;
    int increment = 2;

    int numFields = (hiValue - loValue) / increment + nullPositions.size() + 1;

    DataValueDescriptor[] dvds = new DataValueDescriptor[numFields];
    int v = -100;
    for (int index = 0; index < numFields; index++) {
      if (nullPositions.contains(index + 1)) {
        dvds[index] = new SQLInteger(); // null
      } else {
        dvds[index] = new SQLInteger(v);
        v += 2;
      }
    }
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
          DataTypeDescriptor.INTEGER, new SQLInteger(), // default (null)
          null, // defaultInfo
          (UUID) null, // table uuid
          (UUID) null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    // check bytes used for offset.
    assertEquals("Should use a 2 byte offset ", 2, rf.getNumOffsetBytes());

    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    // verify values
    v = -100;
    for (int index = 0; index < numFields; index++) {
      if (nullPositions.contains(index + 1)) {
        assertEquals("for index=" + index, null, outDvds[index].getObject());
      }
      else {
        assertEquals("for index=" + index, v, outDvds[index].getObject());
        v += 2;
      }
    }
  }

  /*
   * Test use of three bytes for offset. Using both null and not null null-able
   * Dvds. This test check the starting value required for a three byte offset.
   */
  public void test3BytesOffsetStartingValue() throws Exception {
    int numFields = 20;
    DataValueDescriptor[] dvds = new DataValueDescriptor[numFields];
    String value = "XXXX";
    for (int index = 0; index < numFields; index++) {
        if (index == 0) {
          dvds[index] = new SQLVarchar(value);
          continue;
        }
        if (index == (numFields -1)) {
          dvds[index] = new SQLInteger(10);
          continue;
        }
        dvds[index] = new SQLInteger();
    }
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd = null;
      if (p ==1) {
         cd = new ColumnDescriptor("c" + p, p,
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR), new SQLVarchar(), // default (null)
            null, // defaultInfo
            (UUID) null, // table uuid
            (UUID) null, // default uuid
            0L, // autoincStart
            0L, // autoincInc
            0L,false); // autoincValue
         cdl.add(cd);
         continue;
      }
      
      if (p == dvds.length) {
        cd  = new ColumnDescriptor("c" + p, p,
            DataTypeDescriptor.INTEGER_NOT_NULL, new SQLInteger(10), // default (null)
            null, // defaultInfo
            (UUID) null, // table uuid
            (UUID) null, // default uuid
            0L, // autoincStart
            0L, // autoincInc
            0L,false); // autoincValue
        
        cdl.add(cd);
        continue;
      }
      cd  = new ColumnDescriptor("c" + p, p,
          DataTypeDescriptor.INTEGER, new SQLInteger(10), // default (null)
          null, // defaultInfo
          (UUID) null, // table uuid
          (UUID) null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
      
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    // check bytes used for offset.
    assertEquals("Should use a 3 byte offset ", 3, rf.getNumOffsetBytes());

    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    assert (dvds.length == outDvds.length);
  }

  /*
   * Test use of four bytes for offset. Using both null and not null null-able
   * Dvds. This test check the starting value required for a four byte offset.
   */
  public void test4BytesOffsetEndValue() throws Exception {
    int numFields = 5018;
    DataValueDescriptor[] dvds = new DataValueDescriptor[numFields];
    for (int index = 0; index < 256; index++) {
      dvds[index] = new SQLVarchar("XXXX");
    }
    for (int index = 256 ; index < 5017 ; index++) {
      dvds[index] = new SQLInteger();
    }
    for(int index = 5017 ; index < 5018 ; index++) {
      dvds[index] = new SQLChar("XXXX");
    }
    // create column descriptors
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= 256; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR), new SQLVarchar(), // default (null)
            null, // defaultInfo
            (UUID) null, // table uuid
            (UUID) null, // default uuid
            0L, // autoincStart
            0L, // autoincInc
            0L,false); // autoincValue
         
      cdl.add(cd);
      
    }
    
    for (int p = 257; p <= 5017; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
            DataTypeDescriptor.INTEGER, new SQLInteger(), // default (null)
            null, // defaultInfo
            (UUID) null, // table uuid
            (UUID) null, // default uuid
            0L, // autoincStart
            0L, // autoincInc
            0L,false); // autoincValue
         
      cdl.add(cd);
      
    }
    
    for (int p = 5018; p <= 5018; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.CHAR, false, 1), new SQLChar(), // default (null)
            null, // defaultInfo
            (UUID) null, // table uuid
            (UUID) null, // default uuid
            0L, // autoincStart
            0L, // autoincInc
            0L,false); // autoincValue
         
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    // check bytes used for offset.
    assertEquals("Should use a 4 byte offset ", 4, rf.getNumOffsetBytes());

    byte[] bytes = rf.generateBytes(dvds);

    // test getting the columns back out from the bytes
    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);

    assert (dvds.length == outDvds.length);
  }

  /*
   * Test single default value and one byte offset.
   */
  public void testSingleDVDDefaultValue1ByteOffset () throws Exception {
    DataValueDescriptor[] dvds = new DataValueDescriptor[1];
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
          DataTypeDescriptor.INTEGER, new SQLInteger(1), // default (not null)
          null, // defaultInfo
          (UUID) null, // table uuid
          (UUID) null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    assertEquals("Should use a 1 byte offset ", dvds.length,
        rf.getNumOffsetBytes());
    byte[] bytes = rf.generateBytes(dvds);
    // also the byte array should be of length one ( + 1 for version).
    assertEquals(2, bytes.length);
  }

  /**
   * Test multiple DVDs with Default value and one byte offset.
   */
  public void testMultiDVDDefaultValue1ByteOffset() throws Exception {
    DataValueDescriptor[] dvds = new DataValueDescriptor[25];
    final  int defaultValue = -11;
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
          DataTypeDescriptor.INTEGER, new SQLInteger(-11), // default (not null)
          new DefaultInfoImpl(false, "defalueValue-11", new SQLInteger(-11)), // defaultInfo
          (UUID) null, // table uuid
          (UUID) null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    assertEquals("Should use a 1 byte offset ", 1, rf.getNumOffsetBytes());

    for (int i = 0; i < dvds.length; i++) {
      dvds[i] = new SQLInteger(-11); // same as default value.
    }

    byte[] bytes = rf.generateBytes(dvds);

    // byte array generated should be the same length as dvds array ( + 1 for
    // version)
    assertEquals(dvds.length + 1, bytes.length);

    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);
    for (int index = 0; index < dvds.length; index++) {
      assertEquals("for index=" + index, defaultValue, outDvds[index].getInt());
    }
  }

  /**
   * Test multiple DVDs with Default value and two byte offset.
   */
  public void testMultiDVDDefaultValue2ByteOffset() throws Exception {
    DataValueDescriptor[] dvds = new DataValueDescriptor[100];
    final int defaultValue = -11;
    ColumnDescriptorList cdl = new ColumnDescriptorList();

    for (int p = 1; p <= dvds.length; p++) {
      ColumnDescriptor cd = new ColumnDescriptor("c" + p, p,
          DataTypeDescriptor.INTEGER, new SQLInteger(-11), // default (not null)
          new DefaultInfoImpl(false, "defalueValue-11", new SQLInteger(-11)), // defaultInfo
          (UUID) null, // table uuid
          (UUID) null, // default uuid
          0L, // autoincStart
          0L, // autoincInc
          0L,false); // autoincValue
      cdl.add(cd);
    }
    final RowFormatter rf = new RowFormatter(cdl, null, null, 1, null);
    assertEquals("Should use a 1 byte offset ", 2, rf.getNumOffsetBytes());

    for (int i = 0; i < dvds.length; i++) {
      dvds[i] = new SQLInteger(-11); // same as default value.
    }

    byte[] bytes = rf.generateBytes(dvds);

    // byte array generated should be twice the length of dvds array ( + 1 for
    // version)
    assertEquals((dvds.length * 2) + 1, bytes.length);

    DataValueDescriptor[] outDvds = rf.getAllColumns(bytes);
    for (int index = 0; index < dvds.length; index++) {
      assertEquals("for index=" + index, defaultValue, outDvds[index].getInt());
    }
  }

  /**
   * Test CompactCompositeRegionKey hashCode, equals etc. with and without key
   * bytes. Also checks serialization and correct read of CHAR, VARCHAR,
   * DECIMAL interspersed with fixed/variable width INTs.
   */
  public void testRegionKeyHashEquals() throws Exception {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    // create a table and insert some data
    stmt.execute("create table test.table1 (c1 int, c2 varchar(100), "
        + "c3 char(20) not null, c4 int not null, c5 decimal(30,20), "
        + "c6 varchar(20) default 'testing', c7 real, primary key (c2, c4))");
    // insert some data
    PreparedStatement pstmt = conn.prepareStatement("insert into test.table1 "
        + "(c2, c3, c1, c5, c4) values (?, ?, ?, ?, ?)");
    for (int id = 1; id <= 20; ++id) {
      pstmt.setString(1, "id" + id);
      pstmt.setString(2, "fixed" + id);
      pstmt.setInt(3, id + 1);
      pstmt.setBigDecimal(4, new BigDecimal("0.1" + id));
      pstmt.setInt(5, id);
      pstmt.execute();
    }
    final CompactCompositeRegionKey[] keys1 = new CompactCompositeRegionKey[20];
    final CompactCompositeRegionKey[] keys2 = new CompactCompositeRegionKey[20];
    final CompactCompositeRegionKey[] keys3 = new CompactCompositeRegionKey[20];
    final CompactCompositeRegionKey[] keys4 = new CompactCompositeRegionKey[20];
    final LocalRegion reg = (LocalRegion)Misc.getRegionForTable("TEST.TABLE1",
        true);
    final ExtraTableInfo tabInfo = ((GemFireContainer)reg.getUserAttribute())
        .getExtraTableInfo();
    final RowFormatter rf = tabInfo.getRowFormatter();

    DataValueDescriptor dvd2, dvd4;
    for (int id = 1; id <= 20; ++id) {
      dvd2 = new SQLVarchar("id" + id);
      dvd4 = new SQLInteger(id);
      keys1[id - 1] = new CompactCompositeRegionKey(new DataValueDescriptor[] {
          dvd2, dvd4 }, tabInfo);
    }

    // lookup keys from region directly
    int id;
    for (Object key : reg.keySet()) {
      CompactCompositeRegionKey ccrk = (CompactCompositeRegionKey)key;
      // compare against the keys array above
      id = ccrk.getKeyColumn(1).getInt();
      assertTrue("unexpected ID=" + id, id >= 1 && id <= 20);
      assertNull("unexpected existing value for ID=" + id + ": "
          + keys2[id - 1], keys2[id - 1]);
      keys2[id - 1] = ccrk;
    }
    // now compare the hashCode and equals
    for (id = 1; id <= 20; ++id) {
      assertEquals(id, keys1[id - 1].getKeyColumn(1).getInt());
      assertEquals(id, keys2[id - 1].getKeyColumn(1).getInt());
      assertEquals("id" + id, keys1[id - 1].getKeyColumn(0).getString());
      assertEquals("id" + id, keys2[id - 1].getKeyColumn(0).getString());
      assertEquals(keys1[id - 1].hashCode(), keys2[id - 1].hashCode());
      assertEquals(keys1[id - 1], keys2[id - 1]);
    }
    // clone the key with value bytes and check for hashCode and equals
    for (id = 1; id <= 20; ++id) {
      @Retained @Released final Object valBytes = keys2[id - 1].getValueByteSource();
      try {
        keys3[id - 1] = new CompactCompositeRegionKey((byte[]) valBytes,
            keys2[id - 1].getTableInfo());
      } finally {
        keys2[id - 1].releaseValueByteSource(valBytes);
      }
    }
    for (id = 1; id <= 20; ++id) {
      assertEquals(id, keys2[id - 1].getKeyColumn(1).getInt());
      assertEquals(id, keys3[id - 1].getKeyColumn(1).getInt());
      assertEquals("id" + id, keys2[id - 1].getKeyColumn(0).getString());
      assertEquals("id" + id, keys3[id - 1].getKeyColumn(0).getString());
      assertEquals(keys2[id - 1].hashCode(), keys3[id - 1].hashCode());
      assertEquals(keys2[id - 1], keys3[id - 1]);
    }
    // now force snapshot from key and check again
    for (id = 1; id <= 20; ++id) {
      keys2[id - 1].snapshotKeyFromValue();
    }
    for (id = 1; id <= 20; ++id) {
      assertEquals(id, keys1[id - 1].getKeyColumn(1).getInt());
      assertEquals(id, keys2[id - 1].getKeyColumn(1).getInt());
      assertEquals(id, keys3[id - 1].getKeyColumn(1).getInt());
      assertEquals("id" + id, keys1[id - 1].getKeyColumn(0).getString());
      assertEquals("id" + id, keys2[id - 1].getKeyColumn(0).getString());
      assertEquals("id" + id, keys3[id - 1].getKeyColumn(0).getString());
      assertEquals(keys1[id - 1].hashCode(), keys2[id - 1].hashCode());
      assertEquals(keys1[id - 1].hashCode(), keys3[id - 1].hashCode());
      assertEquals(keys1[id - 1], keys2[id - 1]);
      assertEquals(keys1[id - 1], keys3[id - 1]);
    }

    // create new keys with different values but same keys
    for (id = 1; id <= 20; ++id) {
      keys4[id - 1] = new CompactCompositeRegionKey(
         rf.generateBytes(new DataValueDescriptor[] { new SQLInteger(id + 5),
              new SQLVarchar("id" + id), new SQLChar("testvalue" + id),
              new SQLInteger(id), new SQLDecimal("75." + id),
              new SQLVarchar("test" + id), new SQLReal(10.5F) }), tabInfo);
    }
    // check for equality and hashCode
    for (id = 1; id <= 20; ++id) {
      assertEquals(id, keys1[id - 1].getKeyColumn(1).getInt());
      assertEquals(id, keys4[id - 1].getKeyColumn(1).getInt());
      assertEquals("id" + id, keys1[id - 1].getKeyColumn(0).getString());
      assertEquals("id" + id, keys4[id - 1].getKeyColumn(0).getString());
      assertEquals(keys1[id - 1].hashCode(), keys4[id - 1].hashCode());
      assertEquals(keys2[id - 1].hashCode(), keys4[id - 1].hashCode());
      assertEquals(keys3[id - 1].hashCode(), keys4[id - 1].hashCode());
      assertEquals(keys1[id - 1], keys4[id - 1]);
      assertEquals(keys2[id - 1], keys4[id - 1]);
      assertEquals(keys3[id - 1], keys4[id - 1]);
    }

    // now check for serialization/deserialization
    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    final DataOutputStream out = new DataOutputStream(bos);
    for (id = 1; id <= 20; ++id) {
      DataSerializer.writeObject(keys1[id - 1], out);
      DataSerializer.writeObject(keys2[id - 1], out);
      DataSerializer.writeObject(keys3[id - 1], out);
      DataSerializer.writeObject(keys4[id - 1], out);
    }
    // read back and check results
    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(
        bos.toByteArray()));
    for (id = 1; id <= 20; ++id) {
      for (int i = 1; i <= 4; ++i) {
        keys4[id - 1] = DataSerializer.readObject(in);
        // without ExtraTableInfo this should throw exception
        try {
          assertEquals("id" + id, keys4[id - 1].getKeyColumn(0).getString());
          fail("expected an internal error");
        } catch (RuntimeException err) {
          if (!err.getMessage().contains("tableInfo")) {
            throw err;
          }
        }
        keys4[id - 1].setRegionContext(reg);
        assertEquals("id" + id, keys4[id - 1].getKeyColumn(0).getString());
        assertEquals(id, keys4[id - 1].getKeyColumn(1).getInt());
        assertEquals(keys1[id - 1].hashCode(), keys4[id - 1].hashCode());
        assertEquals(keys2[id - 1].hashCode(), keys4[id - 1].hashCode());
        assertEquals(keys3[id - 1].hashCode(), keys4[id - 1].hashCode());
        assertEquals(keys1[id - 1], keys4[id - 1]);
        assertEquals(keys2[id - 1], keys4[id - 1]);
        assertEquals(keys3[id - 1], keys4[id - 1]);
      }
    }
    assertEquals(0, in.available());

    // now check using Region.get()
    for (id = 1; id <= 20; ++id) {
      assertNotNull("failed1 for ID=" + id, reg.get(keys1[id - 1]));
      assertNotNull("failed2 for ID=" + id, reg.get(keys2[id - 1]));
      assertNotNull("failed3 for ID=" + id, reg.get(keys3[id - 1]));
      assertNotNull("failed4 for ID=" + id, reg.get(keys4[id - 1]));
    }
    // check for default values
    // length of id=1 col should be: 1+6+5+22+4+12+2+2 = 54
    byte[] col1Bytes = (byte[])reg.get(keys1[0]);
    assertNotNull(col1Bytes);
    // TODO: below should be 54 but derby always creates a DefaultInfo tree
    // rather than constant for default
    // The changes added in ColumnDefinitionNode#validateDefault have been
    // commented out for now due to more changes required elsewhere that do
    // generation from defaultTree everytime
    assertEquals(61, col1Bytes.length);
    assertEquals(new SQLVarchar("testing"), rf.getColumn(6, col1Bytes));

    // lastly using an SQL query
    pstmt = conn.prepareStatement("select c2, c5, c6, c3 from test.table1 "
        + "where c4=? and c2=?");
    ResultSet rs;
    String fixedStr;
    for (id = 1; id <= 20; ++id) {
      pstmt.setInt(1, id);
      pstmt.setString(2, "id" + id);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("id" + id, rs.getString(1));
      assertEquals(new BigDecimal("0.1" + id).setScale(20), rs.getObject(2));
      assertEquals(new BigDecimal("0.1" + id).setScale(20), rs.getBigDecimal(2));
      assertEquals("testing", rs.getObject(3));
      fixedStr = "fixed" + id;
      for (int index = fixedStr.length(); index < 20; ++index) {
        fixedStr += ' ';
      }
      assertEquals(fixedStr, rs.getString(4));
      assertEquals(fixedStr, rs.getObject(4));
      assertFalse(rs.next());
    }

    // drop the table
    stmt.execute("drop table test.table1");
  }

  /** test the hash distribution when using serialized bytes */
  public void testHashDistribution_43271() throws Exception {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    // create customers table
    stmt.execute("drop table if exists tpcc.customer");
    stmt.execute("create table tpcc.customer ("
        + "c_w_id         integer        not null,"
        + "c_d_id         integer        not null,"
        + "c_id           integer        not null,"
        + "c_discount     decimal(4,4),"
        + "c_credit       char(2),"
        + "c_last         varchar(16),"
        + "c_first        varchar(16),"
        + "c_credit_lim   decimal(12,2),"
        + "c_balance      decimal(12,2),"
        + "c_ytd_payment  float,"
        + "c_payment_cnt  integer,"
        + "c_delivery_cnt integer,"
        + "c_street_1     varchar(20),"
        + "c_street_2     varchar(20),"
        + "c_city         varchar(20),"
        + "c_state        char(2),"
        + "c_zip          char(9),"
        + "c_phone        char(16),"
        + "c_since        timestamp,"
        + "c_middle       char(2),"
        + "c_data         varchar(500)"
        + ") partition by column(c_w_id) BUCKETS 270");
    stmt.execute("create table tpcc.stock ("
        + "s_w_id       integer       not null,"
        + "s_i_id       integer       not null,"
        + "s_quantity   decimal(4,0),"
        + "s_ytd        decimal(8,2),"
        + "s_order_cnt  integer,"
        + "s_remote_cnt integer,"
        + "s_data       varchar(50),"
        + "s_dist_01    char(24),"
        + "s_dist_02    char(24),"
        + "s_dist_03    char(24),"
        + "s_dist_04    char(24),"
        + "s_dist_05    char(24),"
        + "s_dist_06    char(24),"
        + "s_dist_07    char(24),"
        + "s_dist_08    char(24),"
        + "s_dist_09    char(24),"
        + "s_dist_10    char(24)"
        + ") partition by column (s_w_id) colocate with (tpcc.customer) BUCKETS 270");

    // some inserts into both tables
    final int numInserts = 270;
    final PreparedStatement pstmt = conn.prepareStatement("insert into "
        + "tpcc.customer(c_w_id, c_d_id, c_id) values (?, ?, ?)");
    final PreparedStatement pstmt2 = conn.prepareStatement("insert into "
        + "tpcc.stock(s_w_id, s_i_id) values (?, ?)");
    for (int id = 1; id <= numInserts; id++) {
      pstmt.setInt(1, id);
      pstmt.setInt(2, id << 1);
      pstmt.setInt(3, id + 1);
      pstmt.execute();
      pstmt2.setInt(1, id);
      pstmt2.setInt(2, id + 2);
      pstmt2.execute();
    }
    // check the created bucket IDs in the two tables
    final PartitionedRegion prCust = (PartitionedRegion)Misc
        .getRegionForTable("TPCC.CUSTOMER", true);
    final PartitionedRegion stCust = (PartitionedRegion)Misc
        .getRegionForTable("TPCC.STOCK", true);
    // check for minimum expected buckets
    final Set<Integer> prBucketIds = prCust.getDataStore()
        .getAllLocalBucketIds();
    final Set<Integer> stBucketIds = stCust.getDataStore()
        .getAllLocalBucketIds();
    assertTrue("less than expected buckets created " + prBucketIds.size()
        + ": " + prBucketIds, prBucketIds.size() == (numInserts));
    assertTrue("less than expected buckets created " + stBucketIds.size()
        + ": " + stBucketIds, stBucketIds.size() == (numInserts));
  }

  /** test the hash distribution when using expression resolver */
  public void testHashDistributionUDF_43271() throws Exception {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    // create customers table
    stmt.execute("create table tpcc.customer ("
        + "c_w_id         integer        not null,"
        + "c_d_id         integer        not null,"
        + "c_id           integer        not null,"
        + "c_discount     decimal(4,4),"
        + "c_credit       char(2),"
        + "c_last         varchar(16),"
        + "c_first        varchar(16),"
        + "c_credit_lim   decimal(12,2),"
        + "c_balance      decimal(12,2),"
        + "c_ytd_payment  float,"
        + "c_payment_cnt  integer,"
        + "c_delivery_cnt integer,"
        + "c_street_1     varchar(20),"
        + "c_street_2     varchar(20),"
        + "c_city         varchar(20),"
        + "c_state        char(2),"
        + "c_zip          char(9),"
        + "c_phone        char(16),"
        + "c_since        timestamp,"
        + "c_middle       char(2),"
        + "c_data         varchar(500)"
        + ") partition by(c_w_id)");
    stmt.execute("create table tpcc.stock ("
        + "s_w_id       integer       not null,"
        + "s_i_id       integer       not null,"
        + "s_quantity   decimal(4,0),"
        + "s_ytd        decimal(8,2),"
        + "s_order_cnt  integer,"
        + "s_remote_cnt integer,"
        + "s_data       varchar(50),"
        + "s_dist_01    char(24),"
        + "s_dist_02    char(24),"
        + "s_dist_03    char(24),"
        + "s_dist_04    char(24),"
        + "s_dist_05    char(24),"
        + "s_dist_06    char(24),"
        + "s_dist_07    char(24),"
        + "s_dist_08    char(24),"
        + "s_dist_09    char(24),"
        + "s_dist_10    char(24)"
        + ") partition by (s_w_id) colocate with (tpcc.customer)");

    // some inserts into both tables
    final int numInserts = 72;
    final PreparedStatement pstmt = conn.prepareStatement("insert into "
        + "tpcc.customer(c_w_id, c_d_id, c_id) values (?, ?, ?)");
    final PreparedStatement pstmt2 = conn.prepareStatement("insert into "
        + "tpcc.stock(s_w_id, s_i_id) values (?, ?)");
    for (int id = 1; id <= numInserts; id++) {
      pstmt.setInt(1, id);
      pstmt.setInt(2, id << 1);
      pstmt.setInt(3, id + 1);
      pstmt.execute();
      pstmt2.setInt(1, id);
      pstmt2.setInt(2, id + 2);
      pstmt2.execute();
    }
    // check the created bucket IDs in the two tables
    final PartitionedRegion prCust = (PartitionedRegion)Misc
        .getRegionForTable("TPCC.CUSTOMER", true);
    final PartitionedRegion stCust = (PartitionedRegion)Misc
        .getRegionForTable("TPCC.STOCK", true);
    // check for minimum expected buckets
    final Set<Integer> prBucketIds = prCust.getDataStore()
        .getAllLocalBucketIds();
    final Set<Integer> stBucketIds = stCust.getDataStore()
        .getAllLocalBucketIds();
    assertEquals("less than expected buckets created " + prBucketIds.size()
        + ": " + prBucketIds, numInserts, prBucketIds.size());
    assertEquals("less than expected buckets created " + stBucketIds.size()
        + ": " + stBucketIds, numInserts, stBucketIds.size());
  }
  
  public void testProjectionWithClobs() throws SQLException {
    Properties cp = new Properties();
    // default is too server...
    cp.setProperty("host-data", "true");
    //cp.setProperty("log-level", "fine");

    cp.put(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, "Soubhik");
    cp.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "Soubhik");
    cp.setProperty("mcast-port", String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));

    Connection conn = TestUtil.getConnection(cp);
    Statement st = conn.createStatement();
    st
        .execute("create table Layout (     uuid_ varchar(75),      plid bigint not null primary key,       " +
                        "groupId bigint, companyId bigint,       privateLayout smallint, layoutId bigint,        " +
                        "parentLayoutId bigint,  name varchar(4000),     title varchar(4000),    description varchar(4000),      " +
                        "type_ varchar(75),      typeSettings clob,      hidden_ smallint,       friendlyURL varchar(255),       " +
                        "iconImage smallint,     iconImageId bigint,     themeId varchar(75),    colorSchemeId varchar(75),      " +
                        "wapThemeId varchar(75), wapColorSchemeId varchar(75),   css varchar(4000),      priority integer,       " +
                        "layoutPrototypeId bigint,       dlFolderId bigint)");
    
    st.execute("insert into Layout (uuid_, groupId, companyId, privateLayout, layoutId, parentLayoutId, name, title, description, type_, " +
                "typeSettings, hidden_, friendlyURL, iconImage, iconImageId, themeId, colorSchemeId, wapThemeId, wapColorSchemeId, css, " +
                "priority, layoutPrototypeId, dlFolderId, plid) values ('3333-2332-3323-3332', 112, 3323, 33, 3323, 33232, 'NAME : CHAKRABORTY, KUMAR CHAKRABORTY, PRANAB KUMAR CHAKRABORTY, KUMAR PRANAB KUMAR CHAKRABORTY, SOUBHIK KUMAR PRANAB KUMAR CHAKRABORTY'," +
                " 'title: MISTER, MONSIEUR, だんな (Dan''na), don, जी, ', ' description is self descriptive in name / title. what else ?', 'TYPE-1', " +
                " cast ( 'dafaafsasfasdfasdfasdfasdfasddfasfasdfasd' as clob), 1, 'http://sb.blogspot.com', 1, 1122, 'THEME-1', 'dfadfa', " +
                " 'wapTheme-2' , 'wapColorScheme-11', '<html> <!css type fo scripts> ', 1, 1, 1, 1000) ");
    
    
    /*
    ResultSet rs = st
    .executeQuery("select layoutimpl0_.plid as plid13_0_, layoutimpl0_.uuid_ as uuid2_13_0_, layoutimpl0_.groupId as groupId13_0_," +
                " layoutimpl0_.companyId as companyId13_0_, layoutimpl0_.privateLayout as privateL5_13_0_," +
                " layoutimpl0_.layoutId as layoutId13_0_, layoutimpl0_.parentLayoutId as parentLa7_13_0_," +
                " layoutimpl0_.name as name13_0_, layoutimpl0_.title as title13_0_, layoutimpl0_.description as descrip10_13_0_," +
                " layoutimpl0_.type_ as type11_13_0_, layoutimpl0_.hidden_ as hidden13_13_0_, layoutimpl0_.friendlyURL as friendl14_13_0_," +
                " layoutimpl0_.iconImage as iconImage13_0_, layoutimpl0_.iconImageId as iconIma16_13_0_," +
                " layoutimpl0_.themeId as themeId13_0_, layoutimpl0_.colorSchemeId as colorSc18_13_0_," +
                " layoutimpl0_.wapThemeId as wapThemeId13_0_, layoutimpl0_.wapColorSchemeId as wapColo20_13_0_," +
                " layoutimpl0_.css as css13_0_, layoutimpl0_.priority as priority13_0_," +
                " layoutimpl0_.layoutPrototypeId as layoutP23_13_0_, layoutimpl0_.dlFolderId as dlFolderId13_0_" +
                " from Layout layoutimpl0_ where " +
                    "layoutimpl0_.plid=1000");
    ResultSet rs = st
    .executeQuery("select layoutimpl0_.plid as plid13_0_, " +
                "layoutimpl0_.uuid_ as uuid2_13_0_, " +
                "layoutimpl0_.groupId as groupId13_0_, " +
                "layoutimpl0_.companyId as companyId13_0_, " +
                "layoutimpl0_.privateLayout as privateL5_13_0_, " +
                "layoutimpl0_.layoutId as layoutId13_0_, " +
                "layoutimpl0_.parentLayoutId as parentLa7_13_0_, " +
                "layoutimpl0_.name as name13_0_, " +
                "layoutimpl0_.title as title13_0_, " +
                "layoutimpl0_.description as descrip10_13_0_, " +
                "layoutimpl0_.type_ as type11_13_0_, " +
                "layoutimpl0_.typeSettings as typeSet12_13_0_, " +
                "layoutimpl0_.hidden_ as hidden13_13_0_, " +
                "layoutimpl0_.friendlyURL as friendl14_13_0_, " +
                "layoutimpl0_.iconImage as iconImage13_0_, " +
                "layoutimpl0_.iconImageId as iconIma16_13_0_, " +
                "layoutimpl0_.themeId as themeId13_0_, " +
                "layoutimpl0_.colorSchemeId as colorSc18_13_0_, " +
                "layoutimpl0_.wapThemeId as wapThemeId13_0_, " +
                "layoutimpl0_.wapColorSchemeId as wapColo20_13_0_, " +
                "layoutimpl0_.css as css13_0_, " +
                "layoutimpl0_.priority as priority13_0_, " +
                "layoutimpl0_.layoutPrototypeId as layoutP23_13_0_, " +
                "layoutimpl0_.dlFolderId as dlFolderId13_0_ " +
                "from Layout layoutimpl0_ where layoutimpl0_.plid=1000");
    */
    ResultSet rs = st
    .executeQuery("select " +
                "layoutimpl0_.uuid_ as uuid2_13_0_, " +
                "layoutimpl0_.plid as plid13_0_, " +
                "layoutimpl0_.typeSettings as typeSet12_13_0_, " +
                "layoutimpl0_.hidden_ as hidden13_13_0_, " +
                "title " +
                "from Layout layoutimpl0_ where layoutimpl0_.plid=1000");
    while(rs.next()) {
      assertEquals(rs.getString(1), "3333-2332-3323-3332");
      assertEquals(rs.getLong(2), 1000);
      assertEquals(rs.getString(3), "dafaafsasfasdfasdfasdfasdfasddfasfasdfasd");
      assertEquals(rs.getInt(4), 1);
      assertEquals("title: MISTER, MONSIEUR, だんな (Dan'na), don, जी, ", rs.getString(5));
    }
  }

  public void testDecimalString() throws Exception {
    Random rnd = new Random();
    final int numTimes = 1000000;

    final byte[][] rows = new byte[numTimes][];
    for (int i = 0; i < numTimes; i++) {
      // create a bias towards 1-38 since that is more common
      boolean bias = (rnd.nextInt(5) != 0);
      final int precision;
      if (bias) {
        precision = 1 + rnd.nextInt(38);
      }
      else {
        precision = 1 + rnd.nextInt(TypeId.DECIMAL_MAXWIDTH);
      }
      int scale = rnd.nextInt(precision + 1);
      boolean negate = rnd.nextBoolean();
      int offset;
      char[] chars;
      if (negate) {
        offset = 1;
        chars = new char[precision + 2];
        chars[0] = '-';
      }
      else {
        offset = 0;
        chars = new char[precision + 1];
      }
      for (int j = scale; j < precision; j++) {
        chars[offset++] = (char)(rnd.nextInt(10) + '0');
      }
      chars[offset++] = '.';
      for (int j = 1; j <= scale; j++) {
        chars[offset++] = (char)(rnd.nextInt(10) + '0');
      }
      if (offset != chars.length) {
        fail("offset=" + offset + " chars.length=" + chars.length);
      }
      SQLDecimal dec = new SQLDecimal(chars);
      rows[i] = new byte[dec.getLengthInBytes(null)];
      dec.writeBytes(rows[i], 0, null);
    }

    // first check for correctness
    for (byte[] row : rows) {
      String s1 = SQLDecimal.getAsBigDecimal(row, 0, row.length)
          .toPlainString();
      String s2 = SQLDecimal.getAsString(row, 0, row.length);
      assertEquals(s1, s2);
    }
    // now perf comparison
    long start, end;
    for (int i = 1; i <= 4; i++) {
      start = System.nanoTime();
      for (byte[] row : rows) {
        SQLDecimal.getAsBigDecimal(row, 0, row.length).toPlainString();
      }
      end = System.nanoTime();
      System.out.println("Time taken with BigDecimal "
          + ((end - start) / 1000000.0) + "ms");

      start = System.nanoTime();
      for (byte[] row : rows) {
        SQLDecimal.getAsString(row, 0, row.length);
      }
      end = System.nanoTime();
      System.out.println("Time taken with optimized impl "
          + ((end - start) / 1000000.0) + "ms");
    }
  }

  public void DEBUG_testPXFPerf() throws Exception {
    // create a few arrays and loop over them writing UTF8 bytes to out
    Random rnd = new Random();
    final String availableChars =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";
    final byte[][] byteArrays = new byte[10][];
    int totalBytes = 0;
    for (int i = 0; i < byteArrays.length; i++) {
      final byte[] bytes = byteArrays[i] = new byte[512 + rnd.nextInt(512)];
      for (int j = 0; j < bytes.length; j++) {
        bytes[j] = (byte)availableChars.charAt(rnd.nextInt(availableChars.length()));
      }
      totalBytes += bytes.length;
    }

    final ByteArrayDataOutput out = new ByteArrayDataOutput();
    final int numTimes = 500000;
    final DataTypeDescriptor dtd = DataTypeDescriptor
        .getBuiltInDataTypeDescriptor(Types.VARCHAR);
    // warmups first
    for (int i = 0; i < 100000; i++) {
      for (byte[] bytes : byteArrays) {
        DataTypeUtilities.writeAsUTF8BytesForPXF(bytes, 0, bytes.length, dtd,
            out);
      }
      out.clearForReuse();
    }
    // timed runs
    long start, end;

    for (int cnt = 1; cnt <= 5; cnt++) {
      start = System.nanoTime();
      for (int i = 0; i < numTimes; i++) {
        for (byte[] bytes : byteArrays) {
          DataTypeUtilities.writeAsUTF8BytesForPXF(bytes, 0, bytes.length, dtd,
              out);
        }
        out.clearForReuse();
      }
      end = System.nanoTime();

      double rateMB = (1000.0 * totalBytes * numTimes) / (double)(end - start);
      System.out.println("Total time taken "
          + ((double)(end - start) / 1000000.0) + "ms at the rate of " + rateMB
          + " MB/s");
    }
  }

  public void DEBUG_testBytes() throws Exception {
    Connection conn = TestUtil.getConnection();
    conn.createStatement().execute(
        "create table trade.customers (cid int not null, "
            + "cust_name varchar(100), since date, addr varchar(100), "
            + "tid int, primary key (cid))");
    RowFormatter rf = ((GemFireContainer)Misc.getRegion("/TRADE/CUSTOMERS",
        true, false).getUserAttribute()).getCurrentRowFormatter();
   byte[] bytes1 = new byte[] { 0, 0, 3, -107, 110, 97, 109, 101, 57, 54, 7,
        -45, 4, 4, 97, 100, 100, 114, 101, 115, 115, 32, 105, 115, 32, 110, 97,
        109, 101, 57, 54, 0, 0, 0, 16, 4, 0, 10, 0, 14, 0, 31, 0 };
    byte[] bytes2 =  new byte[] { 0, 0, 3, -107, 110, 97, 109, 101, 57, 49, 55,
        7, -45, 4, 4, 97, 100, 100, 114, 101, 115, 115, 32, 105, 115, 32, 110,
        97, 109, 101, 57, 49, 55, 0, 0, 0, 16, 4, 0, 11, 0, 15, 0, 33, 0 };
    DataValueDescriptor[] dvds1 = new DataValueDescriptor[5];
    DataValueDescriptor[] dvds2 = new DataValueDescriptor[5];
    rf.getColumns(bytes1, dvds1, null);
    rf.getColumns(bytes2, dvds2, null);
    System.out.println("Row1 is: " + Arrays.toString(dvds1));
    System.out.println("Row2 is: " + Arrays.toString(dvds2));
  }
}
