#!/bin/bash
echo -n -e "\033]0;Collecting files from Downloads folder\007"
java -jar HBXFileCollector.jar\
 /source:"/Users/root/Downloads"\
 /target:"/Users/root/Public/Outbox"\
 /fileextension:"mkv,srt,avi,ts"\
 /cleanup:true\
 /collect_exclude:"sample"\
 /cleanup_exclude:"_unpack_,_failed_"\
 /maximum_cleanup_size:128\
 /cleanup_change_delay:14400\
 /cleanup_delay:86400
