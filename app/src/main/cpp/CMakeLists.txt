cmake_minimum_required(VERSION 3.22.1)
project("needle3_jni")

add_library(needle3_jni SHARED needle_jni.cpp)

# If prebuilt libneedle.a is placed in src/main/cpp/lib/${ANDROID_ABI}/libneedle.a, link it:
set(NEEDLE_STATIC_LIB "${CMAKE_CURRENT_SOURCE_DIR}/lib/${ANDROID_ABI}/libneedle.a")
if(EXISTS "${NEEDLE_STATIC_LIB}")
    add_library(needle_static STATIC IMPORTED)
    set_target_properties(needle_static PROPERTIES IMPORTED_LOCATION "${NEEDLE_STATIC_LIB}")
    target_compile_definitions(needle3_jni PRIVATE HAVE_LIBNEEDLE=1)
    target_link_libraries(needle3_jni needle_static log android)
else()
    message(WARNING "libneedle.a not found at ${NEEDLE_STATIC_LIB}; building JNI shim with CLI runner fallback.")
    target_link_libraries(needle3_jni log android)
endif()
