#include "common.h"
#include "guid.h"
#include "CProvider.h"
#include <new>

long g_cRef = 0;
HINSTANCE g_hinst = NULL;

void DllAddRef() { InterlockedIncrement(&g_cRef); }
void DllRelease() { InterlockedDecrement(&g_cRef); }

BOOL APIENTRY DllMain(HINSTANCE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    if (ul_reason_for_call == DLL_PROCESS_ATTACH) {
        g_hinst = hModule;
        DisableThreadLibraryCalls(hModule);
    }
    return TRUE;
}

class CClassFactory : public IClassFactory {
public:
    CClassFactory() : _cRef(1) {}
    IFACEMETHODIMP QueryInterface(REFIID riid, void **ppv) {
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = this;
            AddRef();
            return S_OK;
        }
        *ppv = NULL;
        return E_NOINTERFACE;
    }
    IFACEMETHODIMP_(ULONG) AddRef() { return InterlockedIncrement(&_cRef); }
    IFACEMETHODIMP_(ULONG) Release() {
        LONG cRef = InterlockedDecrement(&_cRef);
        if (!cRef) delete this;
        return cRef;
    }
    IFACEMETHODIMP CreateInstance(IUnknown *pUnkOuter, REFIID riid, void **ppv) {
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;
        CProvider *pProvider = new (std::nothrow) CProvider();
        if (!pProvider) return E_OUTOFMEMORY;
        HRESULT hr = pProvider->QueryInterface(riid, ppv);
        pProvider->Release();
        return hr;
    }
    IFACEMETHODIMP LockServer(BOOL bLock) {
        if (bLock) DllAddRef();
        else DllRelease();
        return S_OK;
    }
private:
    ~CClassFactory() {}
    long _cRef;
};

STDAPI DllGetClassObject(REFIID rclsid, REFIID riid, void **ppv) {
    if (rclsid != CLSID_PamBioProvider) return CLASS_E_CLASSNOTAVAILABLE;
    CClassFactory *pFactory = new (std::nothrow) CClassFactory();
    if (!pFactory) return E_OUTOFMEMORY;
    HRESULT hr = pFactory->QueryInterface(riid, ppv);
    pFactory->Release();
    return hr;
}

STDAPI DllCanUnloadNow() {
    return g_cRef > 0 ? S_FALSE : S_OK;
}

STDAPI DllRegisterServer() {
    HKEY hKey;
    LSTATUS status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, 
        L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{B31B15F6-2A8E-4545-8B90-EEF11C8E5E5A}", 
        0, NULL, 0, KEY_WRITE, NULL, &hKey, NULL);
    if (status == ERROR_SUCCESS) {
        RegSetValueExW(hKey, L"", 0, REG_SZ, (const BYTE*)L"PamBioProvider", sizeof(L"PamBioProvider"));
        RegCloseKey(hKey);
    }
    
    status = RegCreateKeyExW(HKEY_CLASSES_ROOT, 
        L"CLSID\\{B31B15F6-2A8E-4545-8B90-EEF11C8E5E5A}\\InprocServer32", 
        0, NULL, 0, KEY_WRITE, NULL, &hKey, NULL);
    if (status == ERROR_SUCCESS) {
        WCHAR szPath[MAX_PATH];
        GetModuleFileNameW(g_hinst, szPath, MAX_PATH);
        RegSetValueExW(hKey, L"", 0, REG_SZ, (const BYTE*)szPath, (lstrlenW(szPath) + 1) * sizeof(WCHAR));
        RegSetValueExW(hKey, L"ThreadingModel", 0, REG_SZ, (const BYTE*)L"Apartment", sizeof(L"Apartment"));
        RegCloseKey(hKey);
    }
    return S_OK;
}

STDAPI DllUnregisterServer() {
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers\\{B31B15F6-2A8E-4545-8B90-EEF11C8E5E5A}");
    RegDeleteTreeW(HKEY_CLASSES_ROOT, L"CLSID\\{B31B15F6-2A8E-4545-8B90-EEF11C8E5E5A}");
    return S_OK;
}
