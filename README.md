## Overview
SnappyStore is a row oriented, transactional, main-memory distributed data store that is designed for applications that have demanding scalability and availability requirements. You can use the store as a standalone high performance database or as a cache with automatic asynchronous write back to RDBs or as a operational store in "big data" analytic applications. You can manage data entirely using in-memory tables, or you can persist very large tables to local disk store files or to a Hadoop Distributed File System (HDFS) for big data deployments. It provides a low-latency SQL interface to in-memory table data, while seamlessly integrating data that is persisted in HDFS. A single SnappyStore distributed system can be easily scaled out using commodity hardware to support thousands of concurrent clients, and you can also replicate data between multiple clusters over a WAN interface. 

<!--
![GemXD_Architecture](http://gemfirexd.docs.pivotal.io/docs-gemfirexd/common/images/esql_at_a_glance.png)
-->

## History
SnappyStore is a fork of GemFire XD. And, GemFire XD grew out of [GemFire](http://pivotal.io/big-data/pivotal-gemfire), an in-memory data grid developed over a decade. It adds a SQL processing layer on top of GemFire along with several other extensions to integrate with HDFS for working with extremely large volumes of data. GemFire is deployed in hundreds of enterprises worldwide. 
GemFire transitioned from a commercial product developed at [Pivotal](www.pivotal.io) (and [VMWare](www.vmware.com) and [GemStone](www.gemstone.com) before that) to a Open source project(geode) to support a growing community of developers. 

## Getting started
See the instructions below to build the product from source. Once built, you can follow the instructions [here](http://gemfirexd.docs.pivotal.io/docs-gemfirexd/getting_started/book_intro.html) to get started with SnappyStore.


## Repository layout

The snappy/master branch now mostly mimics the layout of Apache Geode layout for GemFire components and a more structured one for GemFireXD components. It uses gradle based build that can build entire GemFire+GemFireXD+hydra sources and run unit tests (running hydra tests still needs to be added).

Some details on the directories in GemFire layout are mentioned below:

 gemfire-shared/src/main/java                   ===> Code shared between GemFire and GemFireXD client.

 gemfire-util/src/main/java                     ===> Utility classes including copy of JOpt Simple and org.json
                                                     adapted for GemFire.

 gemfire-core/src/main/java                     ===> Core GemFire code.

 gemfire-examples/src/main/java                 ===> GemFire Tutorial, Quickstart and helloworld.

 gemfire-examples/src/(dist,osgi)/java          ===> Other GemFire examples.

 gemfire-core/src/main/java/templates           ===> Example implementations for Authentication etc.

 tests/core/src/main/java/com                   ===> The JUnit and DUnit test suite for GemFire.

 tests/core/src/main/java                       ===> Hydra test framework and full GemFire hydra test suite.

 tests/core/src/(version1,version2)             ===> Hydra tests for GemFire product versioning.


The GemFireXD layout is divided into separate logical modules namely:

1) shared

2) client

3) prebuild

4) core

5) tools

6) examples

 gemfirexd/shared/src/main/java                 ===> Code shared between GemFireXD client and server/tools.

 gemfirexd/client/src/main/java                 ===> GemFireXD JDBC client.

 gemfirexd/client/src/main/gfxdcpp,gfxdodbc     ===> gemfirexd/client/src/main/java,gfxdcpp,gfxdodbc

 gemfirexd/csharp                               ===> gemfirexd/client/csharp

 gemfirexd/java/tools,gemfirexd/tools           ===> various dirs under gemfirexd/tools

 gemfirexd/build                                ===> gemfirexd/prebuild

 gemfirexd/java/engine and other core code      ===> gemfirexd/core/src/main/java and other directories under gemfirexd/core

 tests/sql, gfxdperf and other GFXD test code   ===> testing/sql/src/main/java

 demo and other such code                       ===> gemfirexd/examples


## Build targets

GemFireXD now builds completely using gradle. Due to the repository layout changes, the older ant builds no longer work (unless someone takes the effort to change them). The new scripts are much simpler, cleaner and way faster than old ant scripts but are still missing a bunch of old targets. Plan is to add them progressively as required.

  * Switch to "snappy/master" branch if not on that already. Update the branch to the latest version. Then test the build with: ./gradlew cleanAll && ./gradlew buildAll
  * Run a GemFireXD junit test: ./gradlew :snappy-store:snappydata-store-tools:junit -Djunit.single='\*\*/BugsTest.class'
  * Run a GemFireXD dunit test: ./gradlew :snappy-store:snappydata-store-tools:dunitTest -Ddunit.single='**/BugsDUnit.class'
  * Run a GemFireXD wan test:   ./gradlew :snappy-store:snappydata-store-tools:wan -Dwan.single='**/GfxdSerialWanDUnit.class'

Useful build and test targets:
```
./gradlew assemble           -  build all the sources
./gradlew testClasses        -  build all the tests
./gradlew product            -  build and place the product distribution
                                (in build-artifacts/{osname like linux}/store)
./gradlew buildAll           -  build all sources, tests, product, packages (all targets above)
./gradlew cleanAll           -  clean all build and test output
./gradlew junit              -  run junit tests for all components
./gradlew dunit              -  run distributed unit (dunit) tests for all relevant components
./gradlew wan                -  run distributed WAN tests for all relevant components
./gradlew check              -  run all tests including junit, dunit and wan
./gradlew precheckin -Pgfxd  -  cleanAll, buildAll, check
```

Command-line properties:
```
-Djunit.single=<test>        - run a single junit test; the test is provided as path
                               to its .class file (wildcards like '*' and '**' can be
                               used like in gradle includes/excludes patterns)
-Ddunit.single=<test>          - run a single dunit test
-Dwan.single=<test>            - run a single wan dunit test
-DlogLevel=<level>             - set the log-level for the tests (default is "config")
-DsecurityLogLevel=<level>     - set the security-log-level for the tests (default is "config")
-Dskip.wanTest=true|false      - if true, then skip the wan tests when running check/precheckin
```

## Setting up Intellij with gradle

If the build works fine, then import into Intellij:
  * Update Intellij to the latest version just to be sure. Double check that Gradle plugin is enabled in File->Settings->Plugins.
  * Select import project, then point to the GemFireXD directory. Use external Gradle import. Select defaults, next, next ... finish. Ignore "Gradle location is unknown warning". Ensure that a JDK7 installation has been selected.
  * Once import finishes, go to File->Settings->Editor->Code Style->Java. Set the scheme as "Project". Then OK to apply and close it. Next copy codeStyleSettings.xml to .idea directory created by Intellij and then File->Synchronize just to be sure. Check that settings are now applied in File->Settings->Editor->Code Style->Java which should show TabSize, Indent as 2 and continuation indent as 4.
  * If the Gradle tab is not visible immediately, then select it from window list popup at the left-bottom corner of IDE. If you click on that window list icon, then the tabs will appear permanently.
  * Generate GemFireXD required sources by expanding :gemfirexd->Tasks->other. Right click on "generateSources" and run it. The Run item may not be available if indexing is still in progress, so wait for it to finish. This step has to be done only first time, or if ./gradlew clean has been run, or you have made changes to javacc source files.
  * Increase the compiler heap sizes else build can take quite long. In File->Settings->Build, Execution, Deployment->Compiler increase "Build process heap size" to say 1536 or 2048.
  * Test the full build.
  * Open Run->Edit Configurations. Expand Defaults, and select JUnit. Append "/build-artifacts" to the working directory i.e. the directory should be "$MODULE_DIR$/build-artifacts".
  * Try Run->Run... on a test like CreateSchemaTest. Select the normal junit configuration (red+green arrows icon) and not gradle configuration (green round icon).


### Running a unit test

 * When selecting a run configuration for junit, avoid selecting the gradle one (green round icon) otherwise that will launch an external gradle process that will start building the project all over again. Use the normal junit (red+green arrows icon).
 * For JUnit tests, ensure that working directory is "$MODULE_DIR$/build-artifacts" as mentioned before. Otherwise many GemFireXD tests will fail to find the resource files required in many tests. They will also pollute the checkouts with large number of log files etc, so this will allow those to go into build-artifacts that can also be cleaned up easily.

A dunit test can also be executed like above with the difference that multiple JVMs (ChildVM) will get launched in the test run. If you wish to debug the other JVMs, then need to find the appropriate process and attach the IDE debugger to the external JVM process.
