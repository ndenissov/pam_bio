//go:build windows

package main

import (
	"fmt"
	"syscall"
	"unsafe"
	"golang.org/x/term"
)

var (
	advapi32 = syscall.NewLazyDLL("advapi32.dll")
	procLsaOpenPolicy = advapi32.NewProc("LsaOpenPolicy")
	procLsaStorePrivateData = advapi32.NewProc("LsaStorePrivateData")
	procLsaClose = advapi32.NewProc("LsaClose")
)

type lsaUnicodeString struct {
	Length        uint16
	MaximumLength uint16
	Buffer        *uint16
}

type lsaObjectAttributes struct {
	Length                   uint32
	RootDirectory            syscall.Handle
	ObjectName               *lsaUnicodeString
	Attributes               uint32
	SecurityDescriptor       uintptr
	SecurityQualityOfService uintptr
}

func initLsaString(s string) lsaUnicodeString {
	if s == "" {
		return lsaUnicodeString{}
	}
	utf16, _ := syscall.UTF16FromString(s)
	// Subtract 2 for the null terminator since LSA strings don't include it in Length
	length := uint16((len(utf16) - 1) * 2)
	return lsaUnicodeString{
		Length:        length,
		MaximumLength: length + 2,
		Buffer:        &utf16[0],
	}
}

func storeLsaSecret(key, value string) error {
	var handle syscall.Handle
	var objAttr lsaObjectAttributes
	objAttr.Length = uint32(unsafe.Sizeof(objAttr))

	// POLICY_CREATE_SECRET = 0x0020
	status, _, _ := procLsaOpenPolicy.Call(
		0,
		uintptr(unsafe.Pointer(&objAttr)),
		0x0020,
		uintptr(unsafe.Pointer(&handle)),
	)
	if status != 0 {
		return fmt.Errorf("LsaOpenPolicy failed: %x", status)
	}
	defer procLsaClose.Call(uintptr(handle))

	lsaKey := initLsaString(key)
	
	// Value can be null to delete
	var pLsaValue uintptr
	if value != "" {
		lsaValue := initLsaString(value)
		pLsaValue = uintptr(unsafe.Pointer(&lsaValue))
	}

	status, _, _ = procLsaStorePrivateData.Call(
		uintptr(handle),
		uintptr(unsafe.Pointer(&lsaKey)),
		pLsaValue,
	)
	if status != 0 {
		return fmt.Errorf("LsaStorePrivateData failed: %x", status)
	}

	return nil
}

func platformPrePairing() error {
	fmt.Print("Enter Windows password for seamless login (will be stored securely in LSA Secrets): ")
	pwd, err := term.ReadPassword(int(syscall.Stdin))
	fmt.Println()
	if err != nil {
		return err
	}
	
	// Store it securely for SYSTEM to read later
	err = storeLsaSecret("PamBioPassword", string(pwd))
	if err != nil {
		return fmt.Errorf("failed to store LSA secret (are you running as Administrator?): %v", err)
	}
	fmt.Println("✓ Password securely stored in LSA Secrets.")
	return nil
}
