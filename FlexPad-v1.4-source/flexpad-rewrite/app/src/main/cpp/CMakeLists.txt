# Thư viện native cho backend B2 (/dev/uinput) của FlexPad.
cmake_minimum_required(VERSION 3.22.1)
project(flexpad_uinput)

add_library(flexpad_uinput SHARED uinput_jni.cpp)

find_library(log-lib log)
target_link_libraries(flexpad_uinput ${log-lib})
