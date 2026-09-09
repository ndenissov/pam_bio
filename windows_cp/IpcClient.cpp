#include "common.h"
#include <windows.h>
#include <string>
#include <winsock2.h> // for htonl/ntohl

#pragma comment(lib, "ws2_32.lib")

bool SendIpcAuthRequest() {
    HANDLE hPipe = CreateFileW(L"\\\\.\\pipe\\pambio", GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
    if (hPipe == INVALID_HANDLE_VALUE) return false;
    
    // Determine user via GetUserName or similar, for now hardcode or pass empty to let daemon figure it out?
    // LogonUI doesn't have an active user context always, but we can pass generic user string
    std::string reqJson = "{\"action\":\"auth_request\",\"user\":\"WindowsUser\",\"service\":\"logonui\"}";
    
    uint32_t len = htonl((uint32_t)reqJson.length());
    DWORD written;
    WriteFile(hPipe, &len, 4, &written, NULL);
    WriteFile(hPipe, reqJson.c_str(), reqJson.length(), &written, NULL);
    
    uint32_t respLenNet;
    DWORD read;
    if (!ReadFile(hPipe, &respLenNet, 4, &read, NULL) || read != 4) {
        CloseHandle(hPipe);
        return false;
    }
    
    uint32_t respLen = ntohl(respLenNet);
    if (respLen > 1024 * 1024) { CloseHandle(hPipe); return false; }
    
    char* buf = new char[respLen + 1];
    if (!ReadFile(hPipe, buf, respLen, &read, NULL)) {
        delete[] buf;
        CloseHandle(hPipe);
        return false;
    }
    buf[respLen] = '\0';
    CloseHandle(hPipe);
    
    std::string resp(buf);
    delete[] buf;
    
    if (resp.find("\"status\":\"success\"") != std::string::npos || resp.find("\"status\": \"success\"") != std::string::npos) {
        return true;
    }
    return false;
}
