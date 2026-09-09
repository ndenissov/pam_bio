#include "CCredential.h"
#include <shlwapi.h>
#include <credentialprovider.h>
#include <wincred.h>

#define MAX_PW_LEN 256

extern bool SendIpcAuthRequest();
extern std::wstring GetLsaSecret(LPCWSTR secretName);
extern std::wstring GetTargetUsername();

CCredential::CCredential() : _cRef(1) { DllAddRef(); }
CCredential::~CCredential() { DllRelease(); }

IFACEMETHODIMP CCredential::QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IUnknown || riid == IID_ICredentialProviderCredential) {
        *ppv = this;
        AddRef();
        return S_OK;
    }
    *ppv = NULL;
    return E_NOINTERFACE;
}
IFACEMETHODIMP_(ULONG) CCredential::AddRef() { return InterlockedIncrement(&_cRef); }
IFACEMETHODIMP_(ULONG) CCredential::Release() {
    LONG cRef = InterlockedDecrement(&_cRef);
    if (!cRef) delete this;
    return cRef;
}

LPCWSTR CCredential::GetTitleText() {
    LANGID langID = GetUserDefaultUILanguage();
    WORD primaryLang = PRIMARYLANGID(langID);
    
    if (primaryLang == LANG_RUSSIAN || primaryLang == LANG_KAZAK || primaryLang == LANG_KYRGYZ) {
        return L"Вход через PamBio";
    } else if (primaryLang == LANG_CHINESE) {
        return L"通过 PamBio 登录";
    }
    return L"Login with PamBio";
}

IFACEMETHODIMP CCredential::GetFieldState(DWORD dwIndex, CREDENTIAL_PROVIDER_FIELD_STATE *pcpfs, CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE *pcpfis) {
    *pcpfs = CPFS_DISPLAY_IN_BOTH;
    *pcpfis = CPFIS_NONE;
    return S_OK;
}

IFACEMETHODIMP CCredential::GetStringValue(DWORD dwIndex, LPWSTR *ppsz) {
    if (dwIndex == FID_TITLE) {
        LPCWSTR title = GetTitleText();
        return SHStrDupW(title, ppsz);
    }
    return E_NOTIMPL;
}

IFACEMETHODIMP CCredential::GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE *pcpgsr, CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION *pcpcs, LPWSTR *ppszOptionalStatusText, CREDENTIAL_PROVIDER_STATUS_ICON *pcpsiOptionalStatusIcon) {
    *pcpgsr = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
    
    // Call out to daemon to wait for biometric auth
    bool success = SendIpcAuthRequest();
    if (!success) {
        *pcpgsr = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
        return S_OK;
    }
    
    return PackAuthenticationBuffer(pcpcs) == S_OK ? 
        (*pcpgsr = CPGSR_RETURN_CREDENTIAL_FINISHED, S_OK) : E_FAIL;
}

HRESULT CCredential::PackAuthenticationBuffer(CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION *pcpcs) {
    std::wstring password = GetLsaSecret(L"PamBioPassword");
    std::wstring username = GetTargetUsername();
    
    if (password.empty() || username.empty()) return E_FAIL;

    // We must serialize it into KERB_INTERACTIVE_LOGON format
    ULONG ulAuthPackage = 0;
    ULONG ulSize = 0;
    
    // Compute total size: sizeof(KERB_INTERACTIVE_LOGON) + strings
    ulSize = sizeof(KERB_INTERACTIVE_LOGON) + 
             (username.length() * sizeof(WCHAR)) + 
             (password.length() * sizeof(WCHAR));
             
    BYTE* pBuffer = (BYTE*)CoTaskMemAlloc(ulSize);
    if (!pBuffer) return E_OUTOFMEMORY;
    
    ZeroMemory(pBuffer, ulSize);
    
    KERB_INTERACTIVE_LOGON* pKIL = (KERB_INTERACTIVE_LOGON*)pBuffer;
    pKIL->MessageType = KerbInteractiveLogon;
    
    BYTE* pStringData = pBuffer + sizeof(KERB_INTERACTIVE_LOGON);
    
    // Copy Domain (empty for local)
    pKIL->LogonDomainName.Length = 0;
    pKIL->LogonDomainName.MaximumLength = 0;
    pKIL->LogonDomainName.Buffer = (PWSTR)pStringData;
    
    // Copy Username
    pKIL->UserName.Length = username.length() * sizeof(WCHAR);
    pKIL->UserName.MaximumLength = pKIL->UserName.Length;
    pKIL->UserName.Buffer = (PWSTR)pStringData;
    memcpy(pStringData, username.c_str(), pKIL->UserName.Length);
    pStringData += pKIL->UserName.Length;
    
    // Copy Password
    pKIL->Password.Length = password.length() * sizeof(WCHAR);
    pKIL->Password.MaximumLength = pKIL->Password.Length;
    pKIL->Password.Buffer = (PWSTR)pStringData;
    memcpy(pStringData, password.c_str(), pKIL->Password.Length);
    
    pcpcs->rgbSerialization = pBuffer;
    pcpcs->cbSerialization = ulSize;
    pcpcs->clsidCredentialProvider = CLSID_PamBioProvider;
    
    // Lookup authentication package ID for Negotiate
    HANDLE hLsa = NULL;
    LSA_STRING name = { 9, 10, (PCHAR)"Negotiate" };
    LsaConnectUntrusted(&hLsa);
    LsaLookupAuthenticationPackage(hLsa, &name, &ulAuthPackage);
    LsaDeregisterLogonProcess(hLsa);
    
    pcpcs->ulAuthenticationPackage = ulAuthPackage;
    
    return S_OK;
}

// Boilerplate stubs
IFACEMETHODIMP CCredential::Advise(ICredentialProviderCredentialEvents *pcpce) { return S_OK; }
IFACEMETHODIMP CCredential::UnAdvise() { return S_OK; }
IFACEMETHODIMP CCredential::SetSelected(BOOL *pbAutoLogon) { *pbAutoLogon = FALSE; return S_OK; }
IFACEMETHODIMP CCredential::SetDeselected() { return S_OK; }
IFACEMETHODIMP CCredential::SetDeserializedAuthenticationState(DWORD dwState) { return S_OK; }
IFACEMETHODIMP CCredential::GetBitmapValue(DWORD dwIndex, HBITMAP *phbmp) { return E_NOTIMPL; }
IFACEMETHODIMP CCredential::GetCheckboxValue(DWORD dwIndex, BOOL *pbChecked, LPWSTR *ppszLabel) { return E_NOTIMPL; }
IFACEMETHODIMP CCredential::GetSubmitButtonValue(DWORD dwIndex, DWORD *pdwAdjacentTo) { *pdwAdjacentTo = FID_TITLE; return S_OK; }
IFACEMETHODIMP CCredential::GetComboBoxValueCount(DWORD dwIndex, DWORD *pcCount, DWORD *pdwDefault) { return E_NOTIMPL; }
IFACEMETHODIMP CCredential::GetComboBoxValueAt(DWORD dwIndex, DWORD dwItem, LPWSTR *ppszItem) { return E_NOTIMPL; }
IFACEMETHODIMP CCredential::SetStringValue(DWORD dwIndex, LPCWSTR psz) { return S_OK; }
IFACEMETHODIMP CCredential::SetCheckboxValue(DWORD dwIndex, BOOL bChecked) { return S_OK; }
IFACEMETHODIMP CCredential::SetComboBoxSelectedValue(DWORD dwIndex, DWORD dwSelectedItem) { return S_OK; }
IFACEMETHODIMP CCredential::CommandLinkClicked(DWORD dwIndex) { return S_OK; }
IFACEMETHODIMP CCredential::ReportResult(NTSTATUS ntsStatus, NTSTATUS ntsSubstatus, LPWSTR *ppszOptionalStatusText, CREDENTIAL_PROVIDER_STATUS_ICON *pcpsiOptionalStatusIcon) { return S_OK; }
