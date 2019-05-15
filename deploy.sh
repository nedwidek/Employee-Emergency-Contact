#!/bin/bash

if [[ -z "$1" ]]; then
	echo "Mising context"
	exit
fi

mkdir -p $1 && cp -rf deployment/* $1
