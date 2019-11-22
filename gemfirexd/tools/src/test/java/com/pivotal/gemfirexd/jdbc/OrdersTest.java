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
//  OrdersTest.java
//  derby
//
//  Created by Eric Zoerner on 3/18/08.

package com.pivotal.gemfirexd.jdbc;

import java.util.*;
import java.sql.*;
import junit.textui.TestRunner;
import junit.framework.TestSuite;

public class OrdersTest extends JdbcTestBase {
  
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(OrdersTest.class));
  }
  
  public OrdersTest(String name) {
    super(name);
  }
  
	public void testQuery() throws Exception {
    Connection conn = getConnection();
    loadTestData(conn);

		ResultSet rs;
    String queryString = "Select * from Orders where vol > ? and vol < ?";
		PreparedStatement q = conn.prepareStatement(queryString);
    
		for ( int i =0; i < 20; i++) {
			q.setInt(1, i);
			q.setInt(2, i + 100);
			rs = q.executeQuery();
			while (rs.next()); // consume ResultSet
		}
    Statement st = conn.createStatement();
		st.execute("drop table orders");
		st.close();
	}
  
  
  public void loadTestData(Connection conn) throws SQLException {
    String[] securities = { "IBM",
                            "INTC",
                            "MOT",
                            "TEK",
                            "AMD",
                            "CSCO",
                            "DELL",
                            "HP",
                            "SMALL1",
                            "SMALL2" };
    
    Random rand = new Random(System.currentTimeMillis());
    
    Statement s = conn.createStatement();
    
    // We create a table...
    s.execute("drop table if exists orders");
    s.execute("create table orders" +
              "(id int not null , cust_name varchar(200), vol int, " +
              "security_id varchar(10), num int, addr varchar(100))");
    
    // create an index on 'volume'
    s.execute("create index volumeIdx on orders(vol)");
    
    PreparedStatement psInsert =
      conn.prepareStatement("insert into orders values (?, ?, ?, ?, ?, ?)");
        
    int numOfCustomers = 100;
    int numOrdersPerCustomer = 10;
    
    for (int i = 0; i < numOfCustomers; i++) {
      
      // each customer with 100 orders
      for ( int j=0; j < numOrdersPerCustomer; j++) {
				psInsert.setInt(1, i*j);
				psInsert.setString(2, "CustomerWithaLongName" + i);
				psInsert.setInt(3, rand.nextInt(500)); // max volume is 500
				psInsert.setString(4, securities[rand.nextInt(10)]);
				psInsert.setInt(5, 0);
        String queryString = "Emperors club for desperate men, " +
                             "Washington DC, District of Columbia";
				psInsert.setString(6, queryString);
				psInsert.executeUpdate();
      }
    }
  }
}
