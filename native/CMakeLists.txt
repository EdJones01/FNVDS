cmake_minimum_required(VERSION 3.24)
if(POLICY CMP0141)
    cmake_policy(SET CMP0141 NEW)
    set(CMAKE_MSVC_DEBUG_INFORMATION_FORMAT Embedded)
endif()
project(FNVDS LANGUAGES CXX)

if(NOT WIN32 OR NOT MSVC)
    message(FATAL_ERROR "Build this xNVSE plugin with the 32-bit MSVC toolchain (Visual Studio 2022, Win32).")
endif()

if(NOT DEFINED ENV{FNVDS_XNVSE_SDK})
    message(FATAL_ERROR "Set FNVDS_XNVSE_SDK to the extracted xNVSE source folder (the folder containing nvse/PluginAPI.h).")
endif()

set(XNVSE_SDK "$ENV{FNVDS_XNVSE_SDK}")
if(NOT EXISTS "${XNVSE_SDK}/nvse/PluginAPI.h")
    message(FATAL_ERROR "Could not find nvse/PluginAPI.h under FNVDS_XNVSE_SDK=${XNVSE_SDK}")
endif()

add_library(FNVDS SHARED plugin.cpp)
target_link_libraries(FNVDS PRIVATE ws2_32 d3d9)
target_include_directories(FNVDS PRIVATE
    "${XNVSE_SDK}/.."
    "${XNVSE_SDK}"
    "${XNVSE_SDK}/nvse"
)
target_compile_features(FNVDS PRIVATE cxx_std_20)
target_compile_definitions(FNVDS PRIVATE
    RUNTIME
    RUNTIME_VERSION=0x040020D0
)
target_compile_options(FNVDS PRIVATE /FInvse/prefix.h)
set_target_properties(FNVDS PROPERTIES
    OUTPUT_NAME "FNVDS"
    PREFIX ""
    SUFFIX ".dll"
)
