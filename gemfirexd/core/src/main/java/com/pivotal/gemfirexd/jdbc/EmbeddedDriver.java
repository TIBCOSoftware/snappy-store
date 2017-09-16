/*

   Derby - Class org.apache.derby.jdbc.EmbeddedDriver

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
package com.pivotal.gemfirexd.jdbc;

/**
 The embedded JDBC driver (Type 4) for GemFireXD.
 <P>
 The driver automatically supports the correct JDBC specification version
 for the Java Virtual Machine's environment.
 <UL>
 <LI> JDBC 4.0 - Java SE 6
 <LI> JDBC 3.0 - Java 2 - JDK 1.4, J2SE 5.0
 </UL>

 <P>
 Loading this JDBC driver boots the database engine
 within the same Java virtual machine.
 <P>
 The correct code to load the GemFireXD engine using this driver is
 (with approriate try/catch blocks):
 <PRE>
 Class.forName("com.pivotal.gemfirexd.jdbc.EmbeddedDriver").newInstance();

 // or

 new com.pivotal.gemfirexd.jdbc.EmbeddedDriver();


 </PRE>
 When loaded in this way, the class boots the actual JDBC driver indirectly.
 The JDBC specification recommends the Class.ForName method without the .newInstance()
 method call, but adding the newInstance() guarantees
 that GemFireXD will be booted on any Java Virtual Machine.

 <P>
 Note that you do not need to manually load the driver this way if you are
 running on Jave SE 6 or later. In that environment, the driver will be
 automatically loaded for you when your application requests a connection to
 a GemFireXD database.
 <P>
 Any initial error messages are placed in the PrintStream
 supplied by the DriverManager. If the PrintStream is null error messages are
 sent to System.err. Once the GemFireXD engine has set up an error
 logging facility (by default to gemfirexd.log) all subsequent messages are sent to it.
 <P>
 By convention, the class used in the Class.forName() method to
 boot a JDBC driver implements java.sql.Driver.

 This class is not the actual JDBC driver that gets registered with
 the Driver Manager. It proxies requests to the registered GemFireXD JDBC driver.
 <P>
 This is class is only for backward compatibility. Use
 {@link io.snappydata.jdbc.EmbeddedDriver} instead.

 @see java.sql.DriverManager
 @see java.sql.DriverManager#getLogStream
 @see java.sql.Driver
 @see java.sql.SQLException
 */
@Deprecated
public class EmbeddedDriver extends io.snappydata.jdbc.EmbeddedDriver {
}
