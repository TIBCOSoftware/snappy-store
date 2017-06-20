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
package com.gemstone.gemfire.management.internal;

import static org.junit.Assert.*;

import com.gemstone.gemfire.i18n.LogWriterI18n;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.lib.legacy.ClassImposteriser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The JettyHelperTest class is a test suite of test cases testing the
 * contract and functionality of the JettyHelper
 * class.
 * <p/>
 * @author John Blum
 * @see com.gemstone.gemfire.management.internal.JettyHelper
 * @see org.jmock.Mockery
 * @see org.junit.Assert
 * @see org.junit.Test
 */
public class JettyHelperTest {

  private Mockery mockContext;

  @Before
  public void setUp() {
    mockContext = new Mockery();
    mockContext.setImposteriser(ClassImposteriser.INSTANCE);
  }

  @After
  public void tearDown() {
    mockContext.assertIsSatisfied();
    mockContext = null;
  }

  @Test
  public void testSetPortNoBindAddress() throws Exception {
    final LogWriterI18n mockLogWriter = mockContext.mock(LogWriterI18n.class, "testSetPortWithBindAddress.LogWriterI18n");

    mockContext.checking(new Expectations() {{
      allowing(mockLogWriter);
    }});

    final Server jetty = JettyHelper.initJetty(null, 8090, mockLogWriter);

    assertNotNull(jetty);
    assertNotNull(jetty.getConnectors()[0]);
    assertEquals(8090, ((ServerConnector) jetty.getConnectors()[0]).getPort());
  }

  @Test
  public void testSetPortWithBindAddress() throws Exception {
    final LogWriterI18n mockLogWriter = mockContext.mock(LogWriterI18n.class, "testSetPortWithBindAddress.LogWriterI18n");

    mockContext.checking(new Expectations() {{
      allowing(mockLogWriter);
    }});

    final Server jetty = JettyHelper.initJetty("10.123.50.1", 10480, mockLogWriter);

    assertNotNull(jetty);
    assertNotNull(jetty.getConnectors()[0]);
    assertEquals(10480, ((ServerConnector) jetty.getConnectors()[0]).getPort());
  }

}
