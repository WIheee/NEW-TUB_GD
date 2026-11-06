cmake_minimum_required(VERSION 3.22.1)

project(tubgd CXX)

add_library(tubgd SHARED native_scanner.cpp)

target_compile_features(tubgd PRIVATE cxx_std_17)
target_compile_options(tubgd PRIVATE -O2 -fvisibility=hidden)
