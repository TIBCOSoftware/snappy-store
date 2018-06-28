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
 
using System;
using System.Data;
using System.Data.Common;

using NUnit.Framework;

namespace Pivotal.Data.GemFireXD.Tests
{
  /// <summary>
  /// References Mono's SqlConnectionTest.GetSchemaTest for
  /// System.Data.SqlClient though nearly all tests are different.
  /// </summary>
  [TestFixture]
  public class GetSchemaTest : TestBase
  {
    private string[] m_commonTables = { "employee", "numeric_family",
                                        "emp.credit" };

    #region SetUp/TearDown methods

    protected override string[] CommonTablesToCreate()
    {
      return m_commonTables;
    }

    protected override string[] GetTablesToDrop()
    {
      return new string[] { "emp.credit", "numeric_family", "employee" };
    }

    protected override string[] GetProceduresToDrop()
    {
      return new string[] { "test.sp_testproc" };
    }

    #endregion

    #region Tests

    [Test]
    public void GetSchemaTest1()
    {
      int flag = 2;
      DataTable tab1 = m_conn.GetSchema("Schemas");
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          if (col.ColumnName == "SCHEMA_NAME" && (row[col].ToString() == "APP"
              || row[col].ToString() == "EMP")) {
            --flag;
            break;
          }
        }
      }
      Assert.AreEqual(0, flag, "#GS1.1 failed");

      // now test with restrictions
      flag = 1;
      tab1 = m_conn.GetSchema("Schemas", new string[] { null, "E%" });
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          if (col.ColumnName == "SCHEMA_NAME") {
            if (row[col].ToString() == "EMP") {
              // good case
              --flag;
              break;
            }
            else {
              // bad case
              Assert.Fail("#GS1.2 failed for schema: " + row[col]);
            }
          }
        }
      }
      Assert.AreEqual(0, flag, "#GS1.3 failed");
    }

    [Test]
    public void GetSchemaTest2()
    {
      try {
        m_conn.GetSchema(null);
        Assert.Fail("#GS2.1 expected ArgumentException");
      } catch (ArgumentException) {
        // expected exception
      }
      try {
        m_conn.GetSchema("Mono");
        Assert.Fail("#GS2.2 expected ArgumentException");
      } catch (ArgumentException) {
        // expected exception
      }
    }

    [Test]
    public void GetSchemaTest3()
    {
      int flag = 1;
      DataTable tab1 = m_conn.GetSchema("ForeignKeys");
      foreach (DataRow row in tab1.Rows) {
        if (row["TABLE_NAME"].ToString() == "CREDIT") {
          Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS3.1 failed");
          Assert.AreEqual("FK_EC", row["CONSTRAINT_NAME"], "#GS3.2 failed");
          Assert.AreEqual("APP", row["PRIMARYKEY_TABLE_SCHEMA"],
                          "#GS3.3 failed");
          Assert.AreEqual("EMPLOYEE", row["PRIMARYKEY_TABLE_NAME"],
                          "#GS3.4 failed");
          Log("The primary key name is: " +
              row["PRIMARYKEY_NAME"]);
          --flag;
        }
        else {
          Assert.Fail("#GS3.5 unexpected foreign key on table " +
                      row["TABLE_NAME"]);
        }
      }
      Assert.AreEqual(0, flag, "#GS3.6 failed");

      // now test with restrictions
      flag = 1;
      tab1 = m_conn.GetSchema("ForeignKeys", new string[] { null,
                                null, "CREDIT" });
      foreach (DataRow row in tab1.Rows) {
        if (row["TABLE_NAME"].ToString() == "CREDIT") {
          Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS3.7 failed");
          Assert.AreEqual("FK_EC", row["CONSTRAINT_NAME"], "#GS3.8 failed");
          Assert.AreEqual("APP", row["PRIMARYKEY_TABLE_SCHEMA"],
                          "#GS3.9 failed");
          Assert.AreEqual("EMPLOYEE", row["PRIMARYKEY_TABLE_NAME"],
                          "#GS3.10 failed");
          Log("The primary key name is: " +
              row["PRIMARYKEY_NAME"]);
          --flag;
        }
        else {
          Assert.Fail("#GS3.11 unexpected foreign key on table " +
                      row["TABLE_NAME"]);
        }
      }
      Assert.AreEqual(0, flag, "#GS3.12 failed");
    }

    [Test]
    public void GetSchemaTest4()
    {
      int flag = 1;
      DataTable tab1 = m_conn.GetSchema("ForeignKeyColumns");
      foreach (DataRow row in tab1.Rows) {
        if (row["TABLE_NAME"].ToString() == "CREDIT") {
          Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS4.1 failed");
          Assert.AreEqual("FK_EC", row["CONSTRAINT_NAME"], "#GS4.2 failed");
          Assert.AreEqual("APP", row["PRIMARYKEY_TABLE_SCHEMA"],
                          "#GS4.3 failed");
          Assert.AreEqual("EMPLOYEE", row["PRIMARYKEY_TABLE_NAME"],
                          "#GS4.4 failed");
          Assert.AreEqual("ID", row["PRIMARYKEY_COLUMN_NAME"],
                          "#GS4.5 failed");
          Assert.AreEqual("EMPLOYEEID", row["COLUMN_NAME"], "#GS4.6 failed");
          Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS4.7 failed");
          Log("The primary key name is: " +
              row["PRIMARYKEY_NAME"]);
          --flag;
        }
        else {
          Assert.Fail("#GS4.8 unexpected foreign key on table " +
                      row["TABLE_NAME"]);
        }
      }
      Assert.AreEqual(0, flag, "#GS4.9 failed");

      // now test with restrictions
      flag = 1;
      tab1 = m_conn.GetSchema("ForeignKeyColumns", new string[]
                              { null, "EMP", null });
      foreach (DataRow row in tab1.Rows) {
        if (row["TABLE_NAME"].ToString() == "CREDIT") {
          Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS4.10 failed");
          Assert.AreEqual("FK_EC", row["CONSTRAINT_NAME"], "#GS4.11 failed");
          Assert.AreEqual("APP", row["PRIMARYKEY_TABLE_SCHEMA"],
                          "#GS4.12 failed");
          Assert.AreEqual("EMPLOYEE", row["PRIMARYKEY_TABLE_NAME"],
                          "#GS4.13 failed");
          Assert.AreEqual("ID", row["PRIMARYKEY_COLUMN_NAME"],
                          "#GS4.14 failed");
          Assert.AreEqual("EMPLOYEEID", row["COLUMN_NAME"], "#GS4.15 failed");
          Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS4.16 failed");
          --flag;
        }
        else {
          Assert.Fail("#GS4.17 unexpected foreign key on table " +
                      row["TABLE_NAME"]);
        }
      }
      Assert.AreEqual(0, flag, "#GS4.18 failed");
    }

    [Test]
    public void GetSchemaTest5()
    {
      int credFlag = 2, empFlag = 1, numFlag = 1;
      DataTable tab1 = m_conn.GetSchema("Indexes");
      foreach (DataRow row in tab1.Rows) {
        switch (row["TABLE_NAME"].ToString()) {
          case "EMPLOYEE": --empFlag; break;
          case "CREDIT": --credFlag; break;
          case "NUMERIC_FAMILY": --numFlag; break;
          default:
            Assert.Fail("#GS5.1 unexpected index on table " +
                        row["TABLE_NAME"]);
            break;
        }
      }
      Assert.AreEqual(0, empFlag, "#GS5.2 failed");
      Assert.AreEqual(0, credFlag, "#GS5.3 failed");
      Assert.AreEqual(0, numFlag, "#GS5.4 failed");

      // now test with restrictions
      credFlag = 2;
      tab1 = m_conn.GetSchema("Indexes", new string[]
                              { null, "EMP", "CREDIT" } );
      foreach (DataRow row in tab1.Rows) {
        if (row["TABLE_NAME"].ToString() == "CREDIT") {
          Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS5.5 failed");
          Assert.AreEqual("A", row["SORT_TYPE"], "#GS5.6 failed");
          --credFlag;
        }
        else {
          Assert.Fail("#GS5.7 unexpected index on table " +
                      row["TABLE_NAME"]);
        }
      }
      Assert.AreEqual(0, credFlag, "#GS5.8 failed");
    }

    [Test]
    public void GetSchemaTest6()
    {
      int credFlag = 2, empFlag = 1, numFlag = 1;
      DataTable tab1 = m_conn.GetSchema("IndexColumns");
      foreach (DataRow row in tab1.Rows) {
        switch (row["TABLE_NAME"].ToString()) {
          case "EMPLOYEE":
            Assert.AreEqual("ID", row["COLUMN_NAME"], "#GS6.1 failed");
            Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS6.2 failed");
            Assert.AreEqual("A", row["SORT_TYPE"], "#GS6.3 failed");
            --empFlag;
            break;
          case "CREDIT":
            Assert.IsTrue(row["COLUMN_NAME"].ToString() == "EMPLOYEEID" ||
                          row["COLUMN_NAME"].ToString() == "CREDITID",
                          "#GS6.4 failed");
            Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS6.5 failed");
            Assert.AreEqual("A", row["SORT_TYPE"], "#GS6.6 failed");
            --credFlag;
            break;
          case "NUMERIC_FAMILY":
            Assert.AreEqual("ID", row["COLUMN_NAME"], "#GS6.7 failed");
            Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS6.8 failed");
            Assert.AreEqual("A", row["SORT_TYPE"], "#GS6.9 failed");
            --numFlag;
            break;
          default:
            Assert.Fail("#GS6.10 unexpected index on table " +
                        row["TABLE_NAME"]);
            break;
        }
      }
      Assert.AreEqual(0, empFlag, "#GS6.11 failed");
      Assert.AreEqual(0, credFlag, "#GS6.12 failed");
      Assert.AreEqual(0, numFlag, "#GS6.13 failed");

      // now test with restrictions
      credFlag = 2;
      tab1 = m_conn.GetSchema("IndexColumns", new string[]
                              { null, null, "CREDIT" } );
      foreach (DataRow row in tab1.Rows) {
        Assert.AreEqual("CREDIT", row["TABLE_NAME"].ToString(),
                        "#GS6.14 failed");
        Assert.AreEqual("EMP", row["TABLE_SCHEMA"].ToString(),
                        "#GS6.15 failed");
        Assert.IsTrue(row["COLUMN_NAME"].ToString() == "EMPLOYEEID" ||
                      row["COLUMN_NAME"].ToString() == "CREDITID",
                      "#GS6.16 failed");
        Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS6.17 failed");
        Assert.AreEqual("A", row["SORT_TYPE"], "#GS6.18 failed");
        --credFlag;
      }
      Assert.AreEqual(0, credFlag, "#GS6.19 failed");
    }

    [Test]
    public void GetSchemaTest7()
    {
      // create a procedure
      string create_sp = "CREATE PROCEDURE test.sp_testproc" +
       " (inout decval decimal(29, 0), id int, out name varchar(100))" +
       " language java parameter style java" +
       " external name 'tests.TestProcedures.testproc'";
      using (m_cmd = m_conn.CreateCommand()) {
        m_cmd.CommandText = create_sp;
        m_cmd.ExecuteNonQuery();
      }

      int flag = 1;
      DataTable tab1 = m_conn.GetSchema("Procedures");
      foreach (DataRow row in tab1.Rows) {
        if (row["PROCEDURE_SCHEMA"].ToString() == "TEST") {
          Assert.AreEqual("SP_TESTPROC", row["PROCEDURE_NAME"],
                          "#GS7.1 failed");
          Assert.AreEqual("NO_RESULT", row["PROCEDURE_RESULT_TYPE"],
                          "GS7.2 failed");
          --flag;
        }
        else {
          Assert.IsTrue(row["PROCEDURE_SCHEMA"].ToString().StartsWith("SYS") ||
                        row["PROCEDURE_SCHEMA"].ToString().StartsWith("SQLJ"),
                        "#GS7.3 failed");
        }
      }
      Assert.AreEqual(0, flag, "#GS7.4 failed");

      // now test with restrictions
      flag = 1;
      tab1 = m_conn.GetSchema("Procedures", new string[]
                              { null, "T%", "%SP%" });
      foreach (DataRow row in tab1.Rows) {
        if (row["PROCEDURE_SCHEMA"].ToString() == "TEST") {
          Assert.AreEqual("SP_TESTPROC", row["PROCEDURE_NAME"],
                          "#GS7.5 failed");
          Assert.AreEqual("NO_RESULT", row["PROCEDURE_RESULT_TYPE"],
                          "GS7.6 failed");
          --flag;
        }
        else {
          Assert.Fail("#GS7.7 failed");
        }
      }
      Assert.AreEqual(0, flag, "#GS7.8 failed");
    }

    [Test]
    public void GetSchemaTest8()
    {
      // create a procedure
      string create_sp = "CREATE PROCEDURE test.sp_testproc" +
       " (inout decval decimal(29, 0), id int, out name varchar(100))" +
       " language java parameter style java" +
       " external name 'tests.TestProcedures.testproc'";
      using (m_cmd = m_conn.CreateCommand()) {
        m_cmd.CommandText = create_sp;
        m_cmd.ExecuteNonQuery();
      }

      int flag = 3;
      DataTable tab1 = m_conn.GetSchema("ProcedureParameters");
      foreach (DataRow row in tab1.Rows) {
        if (row["PROCEDURE_SCHEMA"].ToString() == "TEST") {
          string paramName = row["PARAMETER_NAME"].ToString();
          Assert.AreEqual("SP_TESTPROC", row["PROCEDURE_NAME"],
                          "#GS8.1 failed");
          Assert.AreEqual(DBNull.Value, row["PROCEDURE_CATALOG"],
                          "#GS8.2 failed");
          switch (paramName) {
            case "DECVAL":
              Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS8.3 failed");
              Assert.AreEqual("INOUT", row["PARAMETER_MODE"], "#GS8.4 failed");
              Assert.AreEqual("DECIMAL", row["TYPE_NAME"], "#GS8.5 failed");
              Assert.AreEqual(62, row["COLUMN_SIZE"], "GS8.6 failed");
              Assert.AreEqual(29, row["PRECISION"], "GS8.7 failed");
              Assert.AreEqual(0, row["SCALE"], "GS8.8 failed");
              Assert.AreEqual(10, row["PRECISION_RADIX"], "GS8.9 failed");
              Assert.AreEqual(DBNull.Value, row["CHARACTER_OCTET_LENGTH"],
                              "GS8.10 failed");
              Assert.AreEqual("YES", row["IS_NULLABLE"], "GS8.11 failed");
              break;
            case "ID":
              Assert.AreEqual(2, row["ORDINAL_POSITION"], "#GS8.12 failed");
              Assert.AreEqual("IN", row["PARAMETER_MODE"], "#GS8.13 failed");
              Assert.AreEqual("INTEGER", row["TYPE_NAME"], "#GS8.14 failed");
              Assert.AreEqual(4, row["COLUMN_SIZE"], "GS8.15 failed");
              Assert.AreEqual(10, row["PRECISION"], "GS8.16 failed");
              Assert.AreEqual(0, row["SCALE"], "GS8.17 failed");
              Assert.AreEqual(10, row["PRECISION_RADIX"], "GS8.18 failed");
              Assert.AreEqual(DBNull.Value, row["CHARACTER_OCTET_LENGTH"],
                              "GS8.19 failed");
              Assert.AreEqual("YES", row["IS_NULLABLE"], "GS8.20 failed");
              break;
            case "NAME":
              Assert.AreEqual(3, row["ORDINAL_POSITION"], "#GS8.21 failed");
              Assert.AreEqual("OUT", row["PARAMETER_MODE"], "#GS8.22 failed");
              Assert.AreEqual("VARCHAR", row["TYPE_NAME"], "#GS8.23 failed");
              Assert.AreEqual(200, row["COLUMN_SIZE"], "GS8.24 failed");
              Assert.AreEqual(100, row["PRECISION"], "GS8.25 failed");
              Assert.AreEqual(DBNull.Value, row["SCALE"], "GS8.26 failed");
              Assert.AreEqual(DBNull.Value, row["PRECISION_RADIX"],
                              "GS8.27 failed");
              Assert.AreEqual(200, row["CHARACTER_OCTET_LENGTH"],
                              "GS8.28 failed");
              Assert.AreEqual("YES", row["IS_NULLABLE"], "GS8.29 failed");
              break;
            default:
              Assert.Fail("#GS8.30 unknown parameter: " + paramName);
              break;
          }
          --flag;
        }
        else {
          Assert.IsTrue(row["PROCEDURE_SCHEMA"].ToString().StartsWith("SYS") ||
                        row["PROCEDURE_SCHEMA"].ToString().StartsWith("SQLJ"),
                        "#GS8.31 failed");
        }
      }
      Assert.AreEqual(0, flag, "#GS8.32 failed");

      // now test with restrictions
      flag = 1;
      tab1 = m_conn.GetSchema("ProcedureParameters", new string[]
                              { null, null, null, "DEC%" });
      foreach (DataRow row in tab1.Rows) {
        if (row["PROCEDURE_SCHEMA"].ToString() == "TEST") {
          string paramName = row["PARAMETER_NAME"].ToString();
          Assert.AreEqual("SP_TESTPROC", row["PROCEDURE_NAME"],
                          "#GS8.33 failed");
          Assert.AreEqual(DBNull.Value, row["PROCEDURE_CATALOG"],
                          "#GS8.34 failed");
          switch (paramName) {
            case "DECVAL":
              Assert.AreEqual(1, row["ORDINAL_POSITION"], "#GS8.35 failed");
              Assert.AreEqual("INOUT", row["PARAMETER_MODE"], "#GS8.36 failed");
              Assert.AreEqual("DECIMAL", row["TYPE_NAME"], "#GS8.37 failed");
              Assert.AreEqual(62, row["COLUMN_SIZE"], "GS8.38 failed");
              Assert.AreEqual(29, row["PRECISION"], "GS8.39 failed");
              Assert.AreEqual(0, row["SCALE"], "GS8.40 failed");
              Assert.AreEqual(10, row["PRECISION_RADIX"], "GS8.41 failed");
              Assert.AreEqual(DBNull.Value, row["CHARACTER_OCTET_LENGTH"],
                              "GS8.42 failed");
              Assert.AreEqual("YES", row["IS_NULLABLE"], "GS8.43 failed");
              break;
            default:
              Assert.Fail("#GS8.44 unknown parameter: " + paramName);
              break;
          }
          --flag;
        }
        else {
          Assert.Fail("#GS8.45 failed");
        }
      }
      Assert.AreEqual(0, flag, "#GS8.46 failed");
    }

    [Test]
    public void GetSchemaTest9()
    {
      int flag = 3;
      DataTable tab1 = m_conn.GetSchema("Tables");
      foreach (DataRow row in tab1.Rows) {
        switch (row["TABLE_NAME"].ToString()) {
          case "EMPLOYEE":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS9.1");
            Assert.AreEqual("APP", row["TABLE_SCHEMA"], "#GS9.2");
            Assert.AreEqual("ROW TABLE", row["TABLE_TYPE"], "#GS9.3");
            --flag;
            break;
          case "CREDIT":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS9.4");
            Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS9.5");
            Assert.AreEqual("ROW TABLE", row["TABLE_TYPE"], "#GS9.6");
            --flag;
            break;
          case "NUMERIC_FAMILY":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS9.7");
            Assert.AreEqual("APP", row["TABLE_SCHEMA"], "#GS9.8");
            Assert.AreEqual("ROW TABLE", row["TABLE_TYPE"], "#GS9.9");
            --flag;
            break;
        }
      }
      Assert.AreEqual(0, flag, "#GS9.10");

      // now test with restrictions
      flag = 3;
      tab1 = m_conn.GetSchema("Tables", new string[]
                              { null, null, "%", "ROW TABLE" });
      foreach (DataRow row in tab1.Rows) {
        switch (row["TABLE_NAME"].ToString()) {
          case "EMPLOYEE":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS9.11");
            Assert.AreEqual("APP", row["TABLE_SCHEMA"], "#GS9.12");
            Assert.AreEqual("ROW TABLE", row["TABLE_TYPE"], "#GS9.13");
            --flag;
            break;
          case "CREDIT":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS9.14");
            Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS9.15");
            Assert.AreEqual("ROW TABLE", row["TABLE_TYPE"], "#GS9.16");
            --flag;
            break;
          case "NUMERIC_FAMILY":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS9.17");
            Assert.AreEqual("APP", row["TABLE_SCHEMA"], "#GS9.18");
            Assert.AreEqual("ROW TABLE", row["TABLE_TYPE"], "#GS9.19");
            --flag;
            break;
          default:
            Assert.Fail("#GS9.20 unexpected table: " + row["TABLE_NAME"]);
            break;
        }
      }
      Assert.AreEqual(0, flag, "#GS9.21");
    }

    [Test]
    public void GetSchemaTest10()
    {
      int flag = 12;
      DataTable tab1 = m_conn.GetSchema("Columns");
      foreach (DataRow row in tab1.Rows) {
        switch (row["TABLE_NAME"].ToString()) {
          case "EMPLOYEE":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS10.1");
            Assert.AreEqual("APP", row["TABLE_SCHEMA"], "#GS10.2");
            --flag;
            break;
          case "CREDIT":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS10.4");
            Assert.AreEqual("EMP", row["TABLE_SCHEMA"], "#GS10.5");
            --flag;
            break;
          case "NUMERIC_FAMILY":
            Assert.AreEqual("", row["TABLE_CATALOG"], "#GS10.7");
            Assert.AreEqual("APP", row["TABLE_SCHEMA"], "#GS10.8");
            --flag;
            break;
        }
      }
      Assert.AreEqual(0, flag, "#GS10.10");
    }

    //[Test]
    public void GetSchemaTest11()
    {
      bool flag = false;
      DataTable tab1 = m_conn.GetSchema("Views");
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          /*
           * TODO: We need to consider multiple values.
           */          
          if (col.ColumnName.ToString() == "user_name"
              && row[col].ToString() == "public") {
            flag = true;
            break;
          }
        }
        if (flag)
          break;
      }
      Assert.AreEqual(true, flag, "#GS11 failed");
    }

    [Test]
    public void GetSchemaTest12()
    {
      bool flag = false;
      DataTable tab1 = m_conn.GetSchema();
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          /*
           * TODO: We need to consider multiple values
           */          
          if (col.ColumnName.ToString() == "CollectionName"
              && row[col].ToString() == "Tables") {
            flag = true;
            break;
          }
        }
        if (flag)
          break;
      }
      Assert.AreEqual(true, flag, "#GS12 failed");
    }

    [Test]
    public void GetSchemaTest13()
    {
      bool flag = false;
      DataTable tab1 = m_conn.GetSchema("RESTRICTIONS");
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          /*
           * TODO: We need to consider multiple values
           */          
          if (col.ColumnName.ToString() == "RestrictionDefault"
              && row[col].ToString() == "FUNCTION_NAME") {
            flag = true;
            break;
          }
        }
        if (flag)
          break;
      }
      Assert.AreEqual(true, flag, "#GS13 failed");
    }

    [Test]
    public void GetSchemaTest14()
    {
      string[] restrictions = new string[1];
      try {
        m_conn.GetSchema("RESTRICTIONS", restrictions);
        Assert.Fail("#G16 expected exception");
      } catch (ArgumentException) {
        // expected exception
      }
    }

    [Test]
    public void GetSchemaTest15()
    {
      bool flag = false;
      DataTable tab1 = m_conn.GetSchema("DataTypes");
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          /*
           * TODO: We need to consider multiple values
           */          
          if (col.ColumnName.ToString() == "TypeName"
              && row[col].ToString() == "DECIMAL") {
            flag = true;
            break;
          }
        }
        if (flag)
          break;
      }
      Assert.AreEqual(true, flag, "#GS15 failed");
    }

    [Test]
    public void GetSchemaTest16()
    {
      bool flag = false;
      DataTable tab1 = m_conn.GetSchema("ReservedWords");
      foreach (DataRow row in tab1.Rows) {
        foreach (DataColumn col in tab1.Columns) {
          /*
           * We need to consider multiple values
           */          
          if (col.ColumnName.ToString() == "ReservedWord"
              && row[col].ToString() == "UPPER") {
            flag = true;
            break;
          }
        }
        if (flag)
          break;
      }
      Assert.AreEqual(true, flag, "#GS16 failed");
    }

    [Test]
    public void GetSchemaTest17()
    {
      string[] restrictions = new string[1];
      try {
        m_conn.GetSchema("Mono", restrictions);
        Assert.Fail("#GS17 expected exception");
      } catch (ArgumentException) {
        // expected exception
      }
    }

    #endregion
  }
}
