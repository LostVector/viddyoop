#!/bin/bash
echo -n -e "\033]0;Brokering to target.domain.com\007"
java -cp HBXFileBroker.jar com.rkuo.Executables.HBXSSHFileBrokerExe\
 /source:"/Users/root/Public/Outbox"\
 /username:root\
 /password:"secure_password_here"\
 /hostname:target.domain.com\
 /target:"/home/root/cloudera/preprocessor/input_new"\
 /fileextension:mkv,avi\
 /minimumtargetdiskspace:64
