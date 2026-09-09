cmake_minimum_required(VERSION 3.10)
project(PamBioCP)

set(CMAKE_CXX_STANDARD 17)

add_library(pambio_cp SHARED
    dll.cpp
    guid.cpp
    CProvider.cpp
    CCredential.cpp
    LsaSecret.cpp
    IpcClient.cpp
    pam_bio_cp.def
)

set_target_properties(pambio_cp PROPERTIES PREFIX "")

if (MSVC)
    set_target_properties(pambio_cp PROPERTIES LINK_FLAGS "/DEF:${CMAKE_CURRENT_SOURCE_DIR}/pam_bio_cp.def")
elseif (MINGW)
    target_link_options(pambio_cp PRIVATE "-static" "-static-libgcc" "-static-libstdc++")
endif()

target_link_libraries(pambio_cp
    secur32
    credui
    shlwapi
    advapi32
    ole32
    user32
    ws2_32
)
