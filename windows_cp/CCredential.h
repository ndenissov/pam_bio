#pragma once
#include "common.h"

class CCredential : public ICredentialProviderCredential {
public:
    CCredential();
    
    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void **ppv);
    IFACEMETHODIMP_(ULONG) AddRef();
    IFACEMETHODIMP_(ULONG) Release();
    
    // ICredentialProviderCredential
    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents *pcpce);
    IFACEMETHODIMP UnAdvise();
    IFACEMETHODIMP SetSelected(BOOL *pbAutoLogon);
    IFACEMETHODIMP SetDeselected();
    IFACEMETHODIMP SetDeserializedAuthenticationState(DWORD dwState);
    IFACEMETHODIMP GetFieldState(DWORD dwIndex, CREDENTIAL_PROVIDER_FIELD_STATE *pcpfs, CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE *pcpfis);
    IFACEMETHODIMP GetStringValue(DWORD dwIndex, LPWSTR *ppsz);
    IFACEMETHODIMP GetBitmapValue(DWORD dwIndex, HBITMAP *phbmp);
    IFACEMETHODIMP GetCheckboxValue(DWORD dwIndex, BOOL *pbChecked, LPWSTR *ppszLabel);
    IFACEMETHODIMP GetSubmitButtonValue(DWORD dwIndex, DWORD *pdwAdjacentTo);
    IFACEMETHODIMP GetComboBoxValueCount(DWORD dwIndex, DWORD *pcCount, DWORD *pdwDefault);
    IFACEMETHODIMP GetComboBoxValueAt(DWORD dwIndex, DWORD dwItem, LPWSTR *ppszItem);
    IFACEMETHODIMP SetStringValue(DWORD dwIndex, LPCWSTR psz);
    IFACEMETHODIMP SetCheckboxValue(DWORD dwIndex, BOOL bChecked);
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD dwIndex, DWORD dwSelectedItem);
    IFACEMETHODIMP CommandLinkClicked(DWORD dwIndex);
    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE *pcpgsr, CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION *pcpcs, LPWSTR *ppszOptionalStatusText, CREDENTIAL_PROVIDER_STATUS_ICON *pcpsiOptionalStatusIcon);
    IFACEMETHODIMP ReportResult(NTSTATUS ntsStatus, NTSTATUS ntsSubstatus, LPWSTR *ppszOptionalStatusText, CREDENTIAL_PROVIDER_STATUS_ICON *pcpsiOptionalStatusIcon);

private:
    ~CCredential();
    long _cRef;
    
    LPCWSTR GetTitleText();
    HRESULT PackAuthenticationBuffer(CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION *pcpcs);
};
