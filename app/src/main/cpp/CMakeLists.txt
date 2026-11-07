cmake_minimum_required(VERSION 3.22.1)

project(codepr CXX)

add_library(codepr SHARED native_scanner.cpp)

target_compile_features(codepr PRIVATE cxx_std_17)
target_compile_options(codepr PRIVATE -O2 -fvisibility=hidden)
