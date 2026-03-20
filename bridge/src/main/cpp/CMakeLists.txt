cmake_minimum_required(VERSION 3.22.1)
project("zenpatch_bridge" VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --- LSPlant prefab ---
find_package(lsplant REQUIRED CONFIG)

# --- Compiler flags ---
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(
        -O3
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -flto
    )
    add_link_options(-Wl,--gc-sections -flto)
endif()

# 16KB page size alignment (Android 16 requirement)
# NDK r27+ handles this via -D_BIONIC_PAGE_SIZE=16384
add_compile_definitions(_BIONIC_PAGE_SIZE=16384)

# --- zenpatch_bridge shared library ---
add_library(
    zenpatch_bridge
    SHARED
    zenpatch_bridge.cpp
    hidden_api_bypass.cpp
)

# Set 16KB alignment in ELF
target_link_options(zenpatch_bridge PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_include_directories(zenpatch_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(
    zenpatch_bridge
    PRIVATE
    lsplant::lsplant
    android
    log
)
