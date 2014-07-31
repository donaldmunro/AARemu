#!/bin/bash
rm test-rgb test-rgb565
cc -g -o test-rgb test-rgb.c -lpthread
cc -g -o test-rgb565 test-rgb565.c -lpthread
