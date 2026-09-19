cmake_minimum_required(VERSION 3.22.1)
project(gnss_dead_reckoning LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MLM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/external/mad-location-manager-lib")
set(MLM_FILTER "${MLM_ROOT}/filter")
set(EIGEN_ROOT "${MLM_FILTER}/external/eigen")
set(GEOGRAPHICLIB_ROOT "${MLM_FILTER}/external/geographiclib")

foreach(required_path
        "${MLM_FILTER}/src/mlm.cpp"
        "${EIGEN_ROOT}/Eigen/Core"
        "${GEOGRAPHICLIB_ROOT}/src/LocalCartesian.cpp")
    if(NOT EXISTS "${required_path}")
        message(FATAL_ERROR
                "Missing recursive native dependency: ${required_path}. "
                "Run: git submodule update --init --recursive")
    endif()
endforeach()

set(PROJECT_VERSION "2.5")
set(PROJECT_VERSION_MAJOR 2)
set(PROJECT_VERSION_MINOR 5)
set(PROJECT_VERSION_PATCH 0)
set(GEOGRAPHICLIB_DATA "")
set(GEOGRAPHICLIB_HAVE_LONG_DOUBLE 0)
set(GEOGRAPHICLIB_WORDS_BIGENDIAN 0)
set(GEOGRAPHICLIB_PRECISION 2)
set(GEOGRAPHICLIB_LIB_TYPE_VAL 0)
configure_file(
        "${GEOGRAPHICLIB_ROOT}/include/GeographicLib/Config.h.in"
        "${CMAKE_CURRENT_BINARY_DIR}/generated/GeographicLib/Config.h"
        @ONLY)

add_library(gnss_dead_reckoning SHARED
        dead_reckoning_jni.cpp
        "${MLM_FILTER}/src/commons.cpp"
        "${MLM_FILTER}/src/gps_acc_fusion_filter.cpp"
        "${MLM_FILTER}/src/mlm.cpp"
        "${GEOGRAPHICLIB_ROOT}/src/Geocentric.cpp"
        "${GEOGRAPHICLIB_ROOT}/src/LocalCartesian.cpp"
        "${GEOGRAPHICLIB_ROOT}/src/Math.cpp")

target_include_directories(gnss_dead_reckoning PRIVATE
        "${MLM_FILTER}/inc"
        "${EIGEN_ROOT}"
        "${GEOGRAPHICLIB_ROOT}/include"
        "${CMAKE_CURRENT_BINARY_DIR}/generated")

target_compile_options(gnss_dead_reckoning PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wno-undefined-var-template)
