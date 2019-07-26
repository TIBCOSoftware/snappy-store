#include "Connection.h"
#include <iostream>
//#include <fstream>
#include <thread>
using namespace io::snappydata::client;
using namespace std;

void stopServer(string command){
  system(command.c_str());
}

int main(int argc, char **argv) {
  Connection conn;
  try {
    string snappyHomeDir(argv[1]);
    string serverStopScript ;
    serverStopScript.append("cd  ").append(snappyHomeDir).append("; ./sbin/snappy-server.sh stop -dir=");
    std::map<std::string, std::string> properties;
    properties.insert(std::pair<std::string, std::string>("load-balance","true"));
    
    conn.open("localhost", 1527,"app","app",properties);
    std::cout << "before stopping server- connected to :"<< conn.getCurrentHostAddress() <<std::endl;
    // creating dummy data
    conn.execute("drop table if exists FailOverTest.test");
    conn.execute("drop schema if exists  FailOverTest");
    conn.execute("create schema FailOverTest");
    conn.execute("create table FailOverTest.test(id int) as select id from range(120000)");
    auto count = conn.executeQuery("select * from FailOverTest.test");
    if(count > 0 )
      {
        std::cout << "Query execute successfully before server stop"<<std::endl;
      }
    //choose server -which one to stop-- for this test running only two server with default port configuration
    int connectPort = conn.getCurrentHostAddress().port;
    string serverDir="./work/";
    if(connectPort != 1528){
      serverDir.append("localhost-server-2");
    }else{
    serverDir.append("localhost-server-1");
    }
    serverStopScript.append(serverDir);

    //stop the server
    std::thread t1(stopServer,serverStopScript);
    t1.join();
    std::this_thread::sleep_for(std::chrono::seconds(30));
    count = conn.executeQuery("select * from FailOverTest.test");
    
    std::cout << "Test executed successfully, no failover tried:"<< conn.getCurrentHostAddress() <<std::endl;
    conn.execute("drop table if exists FailOverTest.test");
    conn.execute("drop schema if exists  FailOverTest");
    conn.close();
    } catch (SQLException& sqle) {
        if(conn.isOpen()) conn.close();
        std::cout<< "ExecuteQuery failed, throws exception"<<std::endl;
        sqle.printStackTrace(std::cout);
    }
}



