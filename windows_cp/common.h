#pragma once
#include <windows.h>
#include <credentialprovider.h>
#include <ntsecapi.h>
#include "guid.h"
#include <string>

extern long g_cRef;
void DllAddRef();
void DllRelease();

enum FIELD_ID {
    FID_LOGO = 0,
    FID_TITLE = 1,
    FID_SUBMIT = 2,
    FID_NUM_FIELDS = 3
};
