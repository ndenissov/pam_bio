
#include <fstream>
#include <ctime>

void LogDebug(const std::wstring& msg) {
    std::wofstream logFile(L"C:\\Windows\\Temp\\pambio_cp.log", std::ios_base::app);
    if (logFile.is_open()) {
        logFile << msg << std::endl;
    }
}
#include "common.h"
#include <ntsecapi.h>
#include <vector>

std::wstring GetLsaSecret(LPCWSTR secretName) {
    LSA_OBJECT_ATTRIBUTES objectAttributes;
    ZeroMemory(&objectAttributes, sizeof(objectAttributes));
    objectAttributes.Length = sizeof(objectAttributes);

    LSA_HANDLE hPolicy = NULL;
    NTSTATUS status = LsaOpenPolicy(NULL, &objectAttributes, POLICY_GET_PRIVATE_INFORMATION, &hPolicy);
    if (status != 0 || hPolicy == NULL) return L"";

    LSA_UNICODE_STRING lucSecretName;
    lucSecretName.Buffer = (LPWSTR)secretName;
    lucSecretName.Length = lstrlenW(secretName) * sizeof(WCHAR);
    lucSecretName.MaximumLength = lucSecretName.Length + sizeof(WCHAR);

    PLSA_UNICODE_STRING pPrivateData = NULL;
    status = LsaRetrievePrivateData(hPolicy, &lucSecretName, &pPrivateData);
    
    std::wstring result = L"";
    if (status == 0 && pPrivateData != NULL) {
        if (pPrivateData->Buffer && pPrivateData->Length > 0) {
            result.assign(pPrivateData->Buffer, pPrivateData->Length / sizeof(WCHAR));
        }
        LsaFreeMemory(pPrivateData);
    }
    LsaClose(hPolicy);
    return result;
}

std::wstring GetTargetUsername() {
    // In a real provider, you might enumerate users or rely on LogonUI to tell you.
    // For simplicity, get the last logged on user.
    HKEY hKey;
    std::wstring username = L"";
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\LogonUI", 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
        WCHAR szUser[256];
        DWORD cbUser = sizeof(szUser);
        if (RegQueryValueExW(hKey, L"LastLoggedOnUser", NULL, NULL, (LPBYTE)szUser, &cbUser) == ERROR_SUCCESS) {
            username = szUser;
            // Remove domain prefix if exists e.g. .\\User
            size_t slash = username.find(L'\\');
            if (slash != std::wstring::npos) {
                username = username.substr(slash + 1);
            }
        }
        RegCloseKey(hKey);
    }
    return username;
}


std::wstring GetTargetDomain() {
    HKEY hKey;
    std::wstring domain = L"";
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\LogonUI", 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
        WCHAR szUser[256];
        DWORD cbUser = sizeof(szUser);
        if (RegQueryValueExW(hKey, L"LastLoggedOnUser", NULL, NULL, (LPBYTE)szUser, &cbUser) == ERROR_SUCCESS) {
            std::wstring user = szUser;
            size_t slash = user.find(L'\\');
            if (slash != std::wstring::npos) {
                domain = user.substr(0, slash);
                if (domain == L".") {
                    domain = L"";
                }
            }
        }
        RegCloseKey(hKey);
    }
    if (domain.empty()) {
        WCHAR computerName[MAX_COMPUTERNAME_LENGTH + 1];
        DWORD size = sizeof(computerName) / sizeof(computerName[0]);
        if (GetComputerNameW(computerName, &size)) {
            domain = computerName;
        }
    }
    return domain;
}
