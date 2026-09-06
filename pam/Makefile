CC      ?= gcc
CFLAGS   = -fPIC -Wall -Wextra -Werror -O2 -D_GNU_SOURCE
LDFLAGS  = -shared -lpam
TARGET   = pam_bio.so

# Allow overriding the PAM include/lib paths (useful for NixOS, cross-compile, etc.)
# Example: make PAM_DIR=/nix/store/…-linux-pam-1.7.2
ifdef PAM_DIR
  CFLAGS  += -I$(PAM_DIR)/include
  LDFLAGS += -L$(PAM_DIR)/lib -Wl,-rpath,$(PAM_DIR)/lib
endif

# Security library dir (where PAM modules are installed)
SECURITY_DIR ?= /lib/security

.PHONY: all clean install uninstall

all: $(TARGET)

$(TARGET): pam_bio.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

install: $(TARGET)
	install -D -m 755 $(TARGET) $(DESTDIR)$(SECURITY_DIR)/$(TARGET)

uninstall:
	rm -f $(DESTDIR)$(SECURITY_DIR)/$(TARGET)

clean:
	rm -f $(TARGET)
