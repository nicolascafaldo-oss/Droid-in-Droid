cmake_minimum_required(VERSION 3.22.1)
project(kestrel_hal LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(kestrel_hal SHARED
    kestrel_virtual_hal.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles2-lib GLESv2)

# libaaudio.so is loaded dynamically via dlopen at runtime so minSdk/ANDROID_PLATFORM 24 builds cleanly
target_link_libraries(kestrel_hal
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles2-lib}
    dl
)

# Align ELF PT_LOAD segments to 16 KB for Android 15+ (targetSdk 35/36) devices
target_link_options(kestrel_hal PRIVATE
    "-Wl,-z,max-page-size=16384"
)
