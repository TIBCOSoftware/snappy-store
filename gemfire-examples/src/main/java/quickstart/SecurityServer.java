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
package quickstart;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;

/**
 * In this example the server starts on a port with security properties for
 * client requests. Please refer to the quickstart guide for instructions on how
 * to run this example.
 * <p>
 * Add $GEMFIRE/lib/gfSecurityImpl.jar to your CLASSPATH before running this example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 5.5
 */
public class SecurityServer {

  public static void main(String[] args) throws Exception {
    
    if (args.length != 2) {
      System.err.println("Usage: java  quickstart.SecurityServer <ldap-server> <ldap-basedn>");
      System.exit(1);
    }
    System.out.println("\nThis example demonstrates Security functionalities with ldap server.\n"
        + "This program is a server, listening on a port for client requests.\n"
        + "The client in this example is configured with security properties.");

    /*
     * User has to provide the ldap server and basedn inputs through the command line argument.
     * args[0] and args[1] are the values of ldap-server and ldap-basedn respectively.
     * example: SecurityServer ldap ou=ldapTesting,dc=pune,dc=gemstone,dc=com
     */
    System.out.println("Setting security properties for server");

    String ldapServer = args[0];
    String ldapBaseDn = args[1];
    
    System.out.println("\nConnecting to the distributed system and creating the cache.");

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "SecurityServer")
        .set("cache-xml-file", "xml/SecurityServer.xml")
        .set("security-client-accessor", "templates.security.XmlAuthorization.create")
        .set("security-client-authenticator", "templates.security.LdapUserAuthenticator.create")
        .set("security-ldap-server", ldapServer)
        .set("security-ldap-basedn", ldapBaseDn)
        .set("security-authz-xml-uri", "xml/authz-ldap.xml")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    System.out.println("Example region, " + exampleRegion.getFullPath() + ", created in cache.");

    System.out.println("\nPlease start the security client, and press Enter when the client finishes all the operations.\n");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();

    // Close the cache and disconnect from GemFire distributed system
    System.out.println("Closing the cache and disconnecting.");
    cache.close();
    System.out.println("Closed the Server Cache");
  }
}
