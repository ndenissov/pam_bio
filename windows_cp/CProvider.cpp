#include "CProvider.h"
#include "CCredential.h"
#include <new>

CProvider::CProvider() : _cRef(1), _pCredential(NULL) { DllAddRef(); }
CProvider::~CProvider() { if (_pCredential) _pCredential->Release(); DllRelease(); }

IFACEMETHODIMP CProvider::QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IUnknown || riid == IID_ICredentialProvider) {
        *ppv = this;
        AddRef();
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}
IFACEMETHODIMP_(ULONG) CProvider::AddRef() { return InterlockedIncrement(&_cRef); }
IFACEMETHODIMP_(ULONG) CProvider::Release() {
    LONG cRef = InterlockedDecrement(&_cRef);
    if (!cRef) delete this;
    return cRef;
}

IFACEMETHODIMP CProvider::SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus, DWORD dwFlags) {
    switch (cpus) {
        case CPUS_LOGON:
        case CPUS_UNLOCK_WORKSTATION:
            _cpus = cpus;
            return S_OK;
        default:
            return E_NOTIMPL;
    }
}

IFACEMETHODIMP CProvider::SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION *pcpcs) { return E_NOTIMPL; }
IFACEMETHODIMP CProvider::Advise(ICredentialProviderEvents *pcpe, UINT_PTR upAdviseContext) { return S_OK; }
IFACEMETHODIMP CProvider::UnAdvise() { return S_OK; }

IFACEMETHODIMP CProvider::GetFieldDescriptorCount(DWORD *pdwCount) {
    *pdwCount = FID_NUM_FIELDS;
    return S_OK;
}

IFACEMETHODIMP CProvider::GetFieldDescriptorAt(DWORD dwIndex, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR **ppcpfd) {
    if (dwIndex >= FID_NUM_FIELDS) return E_INVALIDARG;
    *ppcpfd = (CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR *)CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR));
    if (!*ppcpfd) return E_OUTOFMEMORY;
    
    (*ppcpfd)->dwFieldID = dwIndex;
    (*ppcpfd)->cpft = CPFT_LARGE_TEXT;
    (*ppcpfd)->pszLabel = NULL;
    (*ppcpfd)->guidFieldType = GUID_NULL;
    
    switch (dwIndex) {
        case FID_LOGO:
            (*ppcpfd)->cpft = CPFT_TILE_IMAGE;
            break;
        case FID_TITLE:
            (*ppcpfd)->cpft = CPFT_LARGE_TEXT;
            break;
        case FID_SUBMIT:
            (*ppcpfd)->cpft = CPFT_SUBMIT_BUTTON;
            break;
    }
    return S_OK;
}

IFACEMETHODIMP CProvider::GetCredentialCount(DWORD *pdwCount, DWORD *pdwDefault, BOOL *pbAutoLogonWithDefault) {
    *pdwCount = 1;
    *pdwDefault = 0;
    *pbAutoLogonWithDefault = FALSE;
    return S_OK;
}

IFACEMETHODIMP CProvider::GetCredentialAt(DWORD dwIndex, ICredentialProviderCredential **ppcpc) {
    if (dwIndex != 0) return E_INVALIDARG;
    if (!_pCredential) {
        _pCredential = new (std::nothrow) CCredential();
        if (!_pCredential) return E_OUTOFMEMORY;
    }
    HRESULT hr = _pCredential->QueryInterface(IID_ICredentialProviderCredential, (void**)ppcpc);
    return hr;
}
