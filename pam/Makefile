# Copyright 2026 Nikita Denissov
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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
