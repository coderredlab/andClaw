cmake_minimum_required(VERSION 3.22)
project(andclaw_gateway_snapshot LANGUAGES C)

add_library(gateway-lock-snapshot SHARED gateway_lock_snapshot.c)
target_compile_features(gateway-lock-snapshot PRIVATE c_std_11)
target_compile_options(gateway-lock-snapshot PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)

if(ANDROID)
    target_link_options(gateway-lock-snapshot PRIVATE -Wl,-z,max-page-size=16384)
else()
    # Host regression tests use this same fstat/read implementation, not a substitute reader.
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(gateway-lock-snapshot PRIVATE ${JNI_INCLUDE_DIRS})
endif()
