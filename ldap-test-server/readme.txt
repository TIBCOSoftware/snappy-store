
---------------------------------------------------------------------------------------------------

How to build ldap-test-server jar :
-----------------------------------------------------------

Run below command to build the latest ladap-test-server jar

     command: 
        ./gradlew jar


How to launch/run Standalone LDAP Test server :
-----------------------------------------------------------

  1. First copy the latest ldap-test-server-<version>.jar from ./build/libs/ to ./jars directory.
  2. Then run below command. 

     command: 
        ./start-ldap-server.sh auth.ldif

     Output: 
        LDAP configuration required to make connection to running LDAP server.


---------------------------------------------------------------------------------------------------

