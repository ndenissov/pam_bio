cmake_minimum_required(VERSION 3.10)
project(PamBioCP)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} /DEF:${CMAKE_CURRENT_SOURCE_DIR}/pam_bio_cp.def")

add_library(pambio_cp SHARED
    dll.cpp
    guid.cpp
    CProvider.cpp
    CCredential.cpp
    LsaSecret.cpp
    IpcClient.cpp
)

target_link_libraries(pambio_cp
    secur32
    credui
    shlwapi
    advapi32
    ole32
    user32
)
