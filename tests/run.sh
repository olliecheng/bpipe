#!/bin/bash

TESTS=`find . -maxdepth 1 -type d | grep "[A-Za-z]"`

source testsupport.sh

succ=0
fail=0

for t in $TESTS;
do
	echo "============== $t ================"
	cd "$BASE"/"$t"
	rm -rf .bpipe
	
	if . run.sh;
	then
		echo
		echo "SUCCEEDED"
		echo
		let 'succ=succ+1'
	else
		echo
		echo "FAILED"
		echo
		let 'fail=fail+1'
	fi
done
