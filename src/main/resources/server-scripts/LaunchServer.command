#!/bin/bash
cd "`dirname "$0"`"
java -Xmx2G -XX:MaxPermSize=256M %%ARGUMENTS%% -jar %%SERVERJAR%% "$@"
read -n1 -r -p "Press any key to close..."
