/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.pivotal.gemfirexd.integration.hibernate;

import java.io.File;
import java.net.InetAddress;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;

public class HibernateDialectDUnit extends DistributedSQLTestBase {
  private SessionFactory sessionFactory = null;

  public HibernateDialectDUnit(String name) {
    super(name);
  }

  public void testGemfirexdDialectForHibernate() throws Exception {
    // start some servers
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    String url = TestUtil.getNetProtocol("localhost", netPort);

    // logged due to TraceFunctionException on for unit tests at fine level
    addExpectedException(null, new int[] { 1 },
        EmptyRegionFunctionException.class.getName());

    try {
      // Create the SessionFactory from hibernate.cfg.xml
      sessionFactory = new Configuration()
          .addClass(com.pivotal.gemfirexd.integration.hibernate.MainTable.class)
          .addClass(
              com.pivotal.gemfirexd.integration.hibernate.ColocatedTable.class)
          .setProperty("hibernate.connection.driver_class",
              "com.pivotal.gemfirexd.jdbc.ClientDriver")
          .setProperty("hibernate.connection.url", url)
          .setProperty("hibernate.dialect",
              "com.pivotal.gemfirexd.hibernate.GemFireXDDialect")
          .setProperty("hibernate.show_sql", "true")
          .setProperty("hibernate.hbm2ddl.auto", "create-drop")
          .setProperty("hibernate.current_session_context_class", "thread")
          .setProperty("hibernate.cache.provider_class",
              "org.hibernate.cache.NoCacheProvider")
          .setProperty("hibernate.cache.connection.pool_size", "1")
          .buildSessionFactory();
    } catch (Throwable ex) {
      // Make sure you log the exception, as it might be swallowed
      System.err.println("Initial SessionFactory creation failed." + ex);
      throw new ExceptionInInitializerError(ex);
    }
    assert (sessionFactory != null);

    { // create Main Table
      createAndStoreMain("My First data", new Date());
      Calendar calendar = Calendar.getInstance();
      calendar.set(Calendar.YEAR, 2012); // Year
      calendar.set(Calendar.MONTH, 0); // Month, The first month of the
                                       // year is JANUARY which is 0
      calendar.set(Calendar.DATE, 1); // Day, The first day of the month
                                      // has value 1.
      createAndStoreMain("My Second data", calendar.getTime());
    }

    { // create Second Table
      createAndStoreColocated("My First data", new Date());
      Calendar calendar = Calendar.getInstance();
      calendar.set(Calendar.YEAR, 2012); // Year
      calendar.set(Calendar.MONTH, 0); // Month, The first month of the
                                       // year is JANUARY which is 0
      calendar.set(Calendar.DATE, 1); // Day, The first day of the month
                                      // has value 1.
      createAndStoreColocated("My Second data", calendar.getTime());
    }

    assertEquals(2, executeQueryMain("from MainTable"));
    assertEquals(2, executeQueryColocated("from ColocatedTable"));
    assertEquals(2, executeQueryMain("from MainTable e where e != random()"));
    assertEquals(2, executeQueryMain("from MainTable e where e.date != current_timestamp"));
    assertEquals(2, executeQueryMain("from MainTable e where e.title != user"));
    assertEquals(2, executeQueryMain("from MainTable e where e.title != 'current sqlid'"));

    sessionFactory.close();

    removeExpectedException(null, new int[] { 1 },
        EmptyRegionFunctionException.class.getName());
  }

  private void createAndStoreMain(String title, Date theDate) {
    Session session = sessionFactory.getCurrentSession();
    session.beginTransaction();

    MainTable theTable = new MainTable();
    theTable.setTitle(title);
    theTable.setDate(theDate);
    session.save(theTable);

    session.getTransaction().commit();
  }

  private void createAndStoreColocated(String title, Date theDate) {
    Session session = sessionFactory.getCurrentSession();
    session.beginTransaction();
    ColocatedTable theTable = new ColocatedTable();
    theTable.setTitle(title);
    theTable.setDate(theDate);
    session.save(theTable);
    session.getTransaction().commit();
  }

  private List<?> listresults(String query) {
    Session session = sessionFactory.getCurrentSession();
    session.beginTransaction();
    List<?> result = session.createQuery(query).list();
    session.getTransaction().commit();
    return result;
  }

  private int executeQueryMain(String query) {
    List<?> results = listresults(query);
    int i = 0;
    while (i < results.size()) {
      MainTable theTable = (MainTable)results.get(i);
      System.out.println("MainT: " + theTable.getTitle() + " MTime: "
          + theTable.getDate() + " MID: " + theTable.getId());
      i++;
    }
    return i;
  }

  private int executeQueryColocated(String query) {
    List<?> results = listresults(query);
    int i = 0;
    while (i < results.size()) {
      ColocatedTable theTable = (ColocatedTable)results.get(i);
      System.out.println("ColocatedT: " + theTable.getTitle() + " CTime: "
          + theTable.getDate() + " CID: " + theTable.getId());
      i++;
    }
    return i;
  }
}
