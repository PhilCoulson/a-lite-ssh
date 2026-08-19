cmake_minimum_required(VERSION 3.22.1)
project(alite_ssh C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# Reuse extracted tarballs across ABIs / Gradle CMake invocations.
set(FETCHCONTENT_BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.native-deps"
    CACHE PATH "Shared FetchContent directory" FORCE)
file(MAKE_DIRECTORY "${FETCHCONTENT_BASE_DIR}")
file(LOCK "${FETCHCONTENT_BASE_DIR}/.lock" GUARD FILE TIMEOUT 300)

set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(GEN_FILES OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(INSTALL_MBEDTLS_HEADERS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-2.28.9/mbedtls-2.28.9.tar.bz2
    URL_HASH SHA256=e85ea97aaf78dd6c0a5ba2e54dd5932ffa15f39abfc189c26beef7684630c02b
)
FetchContent_MakeAvailable(mbedtls)

foreach(_mbed_tgt IN ITEMS mbedcrypto mbedx509 mbedtls)
    if(TARGET ${_mbed_tgt})
        set_target_properties(${_mbed_tgt} PROPERTIES POSITION_INDEPENDENT_CODE ON)
    endif()
endforeach()

FetchContent_Declare(
    libssh2
    URL https://github.com/libssh2/libssh2/releases/download/libssh2-1.11.1/libssh2-1.11.1.tar.gz
    URL_HASH SHA256=d9ec76cbe34db98eec3539fe2c899d26b0c837cb3eb466a56b0f109cabf658f7
)
FetchContent_GetProperties(libssh2)
if(NOT libssh2_POPULATED)
    FetchContent_Populate(libssh2)
endif()

set(LIBSSH2_SOURCES
    agent.c
    bcrypt_pbkdf.c
    channel.c
    comp.c
    chacha.c
    cipher-chachapoly.c
    crypt.c
    crypto.c
    global.c
    hostkey.c
    keepalive.c
    kex.c
    knownhost.c
    mac.c
    misc.c
    packet.c
    pem.c
    poly1305.c
    publickey.c
    scp.c
    session.c
    sftp.c
    transport.c
    userauth.c
    userauth_kbd_packet.c
    version.c
)
list(TRANSFORM LIBSSH2_SOURCES PREPEND "${libssh2_SOURCE_DIR}/src/")

add_library(ssh2 STATIC ${LIBSSH2_SOURCES})
target_compile_definitions(ssh2 PRIVATE
    HAVE_CONFIG_H
    LIBSSH2_MBEDTLS
)
target_include_directories(ssh2
    PUBLIC
        "${libssh2_SOURCE_DIR}/include"
    PRIVATE
        "${libssh2_SOURCE_DIR}/src"
        "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_link_libraries(ssh2 PUBLIC mbedtls mbedx509 mbedcrypto)

add_library(alite_ssh SHARED
    ssh_tunnel.c
    jni_bridge.c
)
target_include_directories(alite_ssh PRIVATE
    "${libssh2_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_link_libraries(alite_ssh
    PRIVATE
        ssh2
        log
)
target_compile_options(alite_ssh PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
