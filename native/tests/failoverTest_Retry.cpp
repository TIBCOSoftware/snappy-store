#include "Connection.h"
#include <iostream>
#include <thread>
#include <string>
using namespace io::snappydata::client;
using namespace std;

int main(int argc, char **argv) {
  Connection conn;
  try {

    string locatorIpAddr(argv[1]);
    string locatorPort(argv[2]);
    string timeDuration(argv[3]);
    std::cout << "time duraation::" << timeDuration;
    std::map<std::string, std::string> properties;
    properties.insert(
        std::pair<std::string, std::string>("load-balance", "true"));
    properties.insert(
        std::pair<std::string, std::string>("route-query", "false"));

    conn.open(locatorIpAddr, stoi(locatorPort), "app", "app", properties);
    std::cout << "before stopping server- connected to :"
        << conn.getCurrentHostAddress() << std::endl;

    for (int i = 0; i < 10; ++i) {
      try {
        std::this_thread::sleep_for(std::chrono::seconds(stoi(timeDuration)));

        auto count = conn.executeQuery("select * from app.orders");

        std::cout << "Query execute successfully with server "
            << conn.getCurrentHostAddress() << std::endl;
      } catch (SQLException& sqle) {
        sqle.printStackTrace(std::cout);
        continue;
      }

    }
    conn.close();
  } catch (SQLException& sqle) {
    if (conn.isOpen()) conn.close();
    std::cout << "ExecuteQuery failed, throws exception" << std::endl;
    sqle.printStackTrace(std::cout);
  }
}
