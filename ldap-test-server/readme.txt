
---------------------------------------------------------------------------------------------------

How to build ldap-test-server jar :
-----------------------------------------------------------

Run below command to build the latest ladap-test-server jar

     ./gradlew build

The start-ldap-server.sh script will automatically launch this the first time if it is
unable to find the jar in dist directory. However, it will not check for jar being
latest so that will need to be done explicitly in case of any code changes later.


How to launch/run Standalone LDAP Test server :
-----------------------------------------------------------

  Run the command below.

     ./start-ldap-server.sh auth.ldif

     A default auth.ldif has been provided. It contains users gemfire1 to gemfire10 having password
     same as user name. LDAP groups gemGroup1 to gemGroup6 are also defined -- see membership
     details in auth.ldif (or comments in LdapGroupAuthTest.java).
     One can copy and change auth.ldif as desired and pass that instead.

     Output: 
        LDAP configuration required to make connection to running LDAP server. This
        adds a few debugging flags (like TraceAuthentication, security-log-level=finest) which
        can be removed/adjusted as required.


---------------------------------------------------------------------------------------------------

