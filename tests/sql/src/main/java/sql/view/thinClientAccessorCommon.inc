hydra.Prms-testDescription = "view testing hydra common configuration using thin client";

include $JTESTS/hydraconfig/hydraparams1.inc;
include $JTESTS/hydraconfig/gemfirexd/topology_client_locator.inc;

hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/gemfirexd.jar;
hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/gemfirexd-client.jar;
hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/gemfirexd-tools.jar;
hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/jline-1.0.jar;

hydra.VmPrms-extraVMArgs   = fcn "hydra.TestConfigFcns.duplicate
                                 (\"-Xmx128m\", ${locatorHosts}, true)"
                             ncf
                             ,
                             fcn "hydra.TestConfigFcns.duplicate
                                  (\"-Xms512m -Xmx512m\", ${serverHosts}, true)"
                             ncf
                              ,
                             fcn "hydra.TestConfigFcns.duplicate
                                  (\"-Xmx256m \", ${clientHosts}, true)"
                             ncf;
                             
hydra.Prms-manageDerbyServer = true;

hydra.Prms-totalTaskTimeSec           = 600;
hydra.Prms-maxResultWaitSec           = 480;
hydra.Prms-maxCloseTaskResultWaitSec  = 300;
hydra.Prms-serialExecution            = false;	

hydra.gemfirexd.FabricServerPrms-persistDD = fcn "hydra.TestConfigFcns.oneof(\"true\", \"false\")" ncf;
                   
sql.SQLPrms-initCustomersSizePerThread = RANGE 20 50 EGNAR;
sql.SQLPrms-initSecuritiesSizePerThread = RANGE 10 30 EGNAR;      

THREADGROUP locator
  totalThreads = fcn  ${locatorHosts} * ${locatorVMsPerHost} * ${locatorThreadsPerVM} ncf
  totalVMs     = fcn "(${locatorHosts} * ${locatorVMsPerHost})" ncf
  clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"locator\",
                                ${locatorHosts}, true)" ncf;
  
THREADGROUP serverThreads
            totalThreads = fcn ${serverHosts} * ${serverVMsPerHost} * ${serverThreadsPerVM} ncf
            totalVMs     = fcn ${serverHosts} * ${serverVMsPerHost} ncf
            clientNames  = fcn "hydra.TestConfigFcns.generateNames
                                (\"server\", ${serverHosts}, true)" ncf;                                
THREADGROUP clientThreads
            totalThreads = fcn ${clientHosts} * ${clientVMsPerHost} * ${clientThreadsPerVM} -1  ncf
            totalVMs     = fcn ${clientHosts} * ${clientVMsPerHost} ncf
            clientNames  = fcn "hydra.TestConfigFcns.generateNames
                                (\"client\", ${clientHosts}, true)" ncf;
                                
THREADGROUP ddlThread
            totalThreads = 1
            totalVMs     = 1
            clientNames  = fcn "hydra.TestConfigFcns.generateNames
                                (\"client\", ${clientHosts}, true)" ncf;      

INITTASK taskClass = sql.view.ViewTest
		 taskMethod = HydraTask_createGfxdLocatorTask
         threadGroups = locator;
         
INITTASK taskClass = sql.view.ViewTest
		 taskMethod = HydraTask_startGfxdLocatorTask
		 runMode = always
         threadGroups = locator;
                                
INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_initializeServer
         runMode = always
         threadGroups = serverThreads;
         
INITTASK taskClass   = sql.view.ViewTest 
		 taskMethod  = HydraTask_startFabricServer
		 runMode = always
         threadGroups = serverThreads;           
		
INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_startNetworkServer
		 runMode = always
		 threadGroups =  serverThreads;    

INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_initClient
		 threadGroups = clientThreads, ddlThread;				

INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_createDiscDB
		 threadGroups = ddlThread;

INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_createDiscSchemas
		 threadGroups = ddlThread;

INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_createDiscTables
		 threadGroups = ddlThread;	  

INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_createDiscViews
		 threadGroups = ddlThread;
		 
INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_createGfxdSchemasByClients
		 threadGroups = ddlThread; 		
	
INITTASK taskClass   = sql.view.ViewTest 
		 taskMethod  = HydraTask_createGfxdTablesByClients
		 threadGroups = ddlThread;      

INITTASK taskClass   = sql.view.ViewTest
		 taskMethod  = HydraTask_createGfxdViewsByClients
		 threadGroups = ddlThread;
  
INITTASK taskClass   = sql.view.ViewTest 
		 taskMethod  = HydraTask_populateViewBaseTablesByClients
  		 threadGroups = ddlThread;     
	            

sql.SQLPrms-hasNetworth = true;		//will create networth table	
sql.SQLPrms-createSchemas = "create schema trade" "create schema emp" ;
sql.view.ViewPrms-viewDDLFilePath = "sql/view/viewDDL.sql";
sql.view.ViewPrms-viewDataFilePath = "sql/view/viewData.sql";

//without cascade delete
sql.SQLPrms-createTablesStatements =
	"create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
	"create table trade.customers (cid int not null, cust_name varchar(100), since date, addr varchar(100), tid int, primary key (cid))"
	"create table trade.networth (cid int not null, cash decimal (30, 20), securities decimal (30, 20), loanlimit int, availloan decimal (30, 20),  tid int, constraint netw_pk primary key (cid), constraint cust_newt_fk foreign key (cid) references trade.customers (cid) on delete restrict, constraint cash_ch check (cash>=0), constraint sec_ch check (securities >=0), constraint availloan_ck check (loanlimit>=availloan and availloan >=0))"
	"create table trade.portfolio (cid int not null, sid int not null, qty int not null, availQty int not null, subTotal decimal(30,20), tid int, constraint portf_pk primary key (cid, sid), constraint cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, constraint sec_fk foreign key (sid) references trade.securities (sec_id), constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))" 
	"create table trade.sellorders (oid int not null constraint orders_pk primary key, cid int, sid int, qty int, ask decimal (30, 20), order_time timestamp, status varchar(10) default 'open', tid int, constraint portf_fk foreign key (cid, sid) references trade.portfolio (cid, sid) on delete restrict, constraint status_ch check (status in ('cancelled', 'open', 'filled')))"
	"create table trade.buyorders(oid int not null constraint buyorders_pk primary key, cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10), tid int, constraint bo_cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id), constraint bo_qty_ck check (qty>=0))"
	"create table trade.txhistory(cid int, oid int, sid int, qty int, price decimal (30, 20), ordertime timestamp, type varchar(10), tid int,  constraint type_ch check (type in ('buy', 'sell')))"
	"create table emp.employees (eid int not null constraint employees_pk primary key, emp_name varchar(100), since date, addr varchar(100), ssn varchar(9))"
	"create table trade.trades (tid int, cid int, eid int, tradedate date, primary Key (tid), foreign key (cid) references trade.customers (cid), constraint emp_fk foreign key (eid) references emp.employees (eid))";

//sql hydra already sets the gemfirexd.table-default-partitioned to true, so need explicitly specify the created tables to be replicated
sql.SQLPrms-gfeDDLExtension =
	" replicate"
        " replicate"
	" replicate"
	" replicate"
	" replicate" 
	" replicate"
	" replicate"
	" replicate"
	" replicate";


hydra.gemfirexd.NetworkServerPrms-names = server;
hydra.gemfirexd.GfxdConfigPrms-networkServerConfig = server;
sql.SQLPrms-useGfxdConfig = true;
