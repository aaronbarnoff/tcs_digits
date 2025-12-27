#!/usr/bin/env bash
mkdir -p solvers
cd solvers

git clone https://github.com/curtisbright/cadical-exhaust.git
cd cadical-exhaust
./configure && make
cd ..
 