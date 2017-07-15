hydra.Prms-testDescription = "view testing hydra common configuration using peer client";

INCLUDE $JTESTS/hydraconfig/hydraparams1.inc;
INCLUDE $JTESTS/hydraconfig/systemparams2.inc;
INCLUDE $JTESTS/hydraconfig/topology_p2p_2.inc;

hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/gemfirexd.jar;
hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/gemfirexd-tools.jar;
hydra.VmPrms-extraClassPaths        += $GEMFIRE/../product-gfxd/lib/jline-1.0.jar;
                             
hydra.Prms-manageDerbyServer = true;
hydra.Prms-totalTaskTimeSec           = 600;
hydra.Prms-maxResultWaitSec           = 480;
hydra.Prms-maxCloseTaskResultWaitSec  = 300;
hydra.Prms-serialExecution            = false;			


sql.SQLPrms-initCustomersSizePerThread = RANGE 20 50 EGNAR;
sql.SQLPrms-initSecuritiesSizePerThread = RANGE 10 30 EGNAR;     



hydra.GemFirePrms-roles +=  fcn "hydra.TestConfigFcns.duplicate
                                  (\"gemfirexd.datastore \", ${${A}Hosts}, false)"
                             ncf 
                             
                             fcn "hydra.TestConfigFcns.duplicate
                                  (\"gemfirexd.accessor \", ${${B}Hosts}, false)"
                             ncf 
                             ;
            
THREADGROUP dataStoreThreads
            totalThreads = fcn "(${${A}Hosts} * ${${A}VMsPerHost} *  ${${A}ThreadsPerVM}) " ncf
            totalVMs     = fcn "(${${A}Hosts} * ${${A}VMsPerHost})" ncf
            clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"${A}\",
                                ${${A}Hosts}, true)" ncf;

THREADGROUP accessorThreads
            totalThreads = fcn "(${${B}Hosts} * ${${B}VMsPerHost} *  ${${B}ThreadsPerVM}) -1  " ncf
            totalVMs     = fcn "(${${B}Hosts} * ${${B}VMsPerHost})" ncf
            clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"${B}\",
                                ${${B}Hosts}, true)" ncf;  

THREADGROUP ddlThread
            totalThreads = 1
            totalVMs     = 1
            clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"${B}\",
                                ${${B}Hosts}, true)" ncf;                                     
                                                                           
INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_initialize
	runMode = always
	threadGroups = dataStoreThreads, ddlThread, accessorThreads;	 
	
INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createDiscDB
	threadGroups = accessorThreads;

INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createDiscSchemas
	threadGroups = ddlThread;

INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createDiscTables
	threadGroups = ddlThread;

INITTASK taskClass   = sql.view.ViewTest 
	taskMethod  = HydraTask_createDiscViews
	threadGroups = ddlThread;
		 
INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createGFXDDB
	runMode = always
	threadGroups = dataStoreThreads;

INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createGFXDDBForAccessors
	threadGroups = accessorThreads;

INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createGFESchemas
	threadGroups = ddlThread;

INITTASK     taskClass   = sql.SQLTest 
	taskMethod  = HydraTask_createGFETables
	threadGroups = ddlThread;

INITTASK taskClass   = sql.view.ViewTest 
	taskMethod  = HydraTask_createGfxdViewsByPeers
	threadGroups = ddlThread;
  
INITTASK taskClass   = sql.view.ViewTest 
	taskMethod  = HydraTask_populateViewBaseTablesByPeers
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


