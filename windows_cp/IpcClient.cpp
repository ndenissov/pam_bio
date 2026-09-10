#include "common.h"
#include <winsock2.h>
#include <windows.h>
#include <cstdint>
#include <string>

#pragma comment(lib, "ws2_32.lib")

bool SendIpcAuthRequest() {
    HANDLE hPipe = CreateFileW(L"\\\\.\\pipe\\pambio", GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
    if (hPipe == INVALID_HANDLE_VALUE) return false;
    
    // Get target username
    extern std::wstring GetTargetUsername();
    std::wstring wuser = GetTargetUsername();
    std::string user = "WindowsUser";
    if (!wuser.empty()) {
        int size_needed = WideCharToMultiByte(CP_UTF8, 0, &wuser[0], (int)wuser.size(), NULL, 0, NULL, NULL);
        std::string strTo(size_needed, 0);
        WideCharToMultiByte(CP_UTF8, 0, &wuser[0], (int)wuser.size(), &strTo[0], size_needed, NULL, NULL);
        user = strTo;
    }
    
    std::string reqJson = "{\"action\":\"auth_request\",\"user\":\"" + user + "\",\"service\":\"Windows Logon\"}";
    
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
