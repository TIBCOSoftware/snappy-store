#!/bin/sh

startup() {

  echo "Starting MySQL from $1"

  /bin/cp $1/my.cnf /home/lises/.my.cnf
  sudo /etc/init.d/mysql start --flush --innodb_flush_log_at_trx_commit --sync_binlog=1
}

startup $PWD
