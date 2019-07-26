#include "Connection.h"
#include <iostream>
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
    std::cout << "before stopping server connected to :"<< conn.getCurrentHostAddress() <<std::endl;

    //executing query in table already present-before stopping server
    auto count = conn.executeQuery("Show databases");
    
    if(count > 0 )
    {
      std::cout << "Query execute successfully stopping server"<<std::endl;
    }
    int connectPort = conn.getCurrentHostAddress().port;
    string serverDir="./work/";
    if(connectPort != 1528){
      serverDir.append("localhost-server-2");
    }else{
      serverDir.append("localhost-server-1");
    }
    serverStopScript.append(serverDir);

    //stopping the server
    std::thread t1(stopServer,serverStopScript);
    t1.join();
    //executing query in table already present-after stopping server
    
    count =  conn.executeQuery("Show databases");
    if(count > 0 )
    {
      std::cout << "Query execute successfully after server stop"<<std::endl;
    }
    std::cout << "Test executed successfully connected to :"<< conn.getCurrentHostAddress() <<std::endl;
    } catch (SQLException& sqle) {
        if(conn.isOpen()) conn.close();
        std::cout<< "ExecuteQuery failed, throws exception"<<std::endl;
        sqle.printStackTrace(std::cout);
    }
}



