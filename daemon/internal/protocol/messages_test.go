// Copyright 2026 Nikita Denissov
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package protocol

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
)

func TestFrameReadWriteJSON(t *testing.T) {
	c1, c2 := net.Pipe()
	defer func() { _ = c1.Close(); _ = c2.Close() }()

	done := make(chan error, 1)
	go func() {
		done <- WriteJSON(c1, IdentifyAckMessage{
			Type:            TypeIdentifyAck,
			Status:          "accepted",
			ServerSignature: "sig123",
		})
	}()

	var got IdentifyAckMessage
	if err := ReadJSON(c2, &got); err != nil {
		t.Fatalf("ReadJSON: %v", err)
	}
	if err := <-done; err != nil {
		t.Fatalf("WriteJSON: %v", err)
	}
	if got.Type != TypeIdentifyAck || got.Status != "accepted" || got.ServerSignature != "sig123" {
		t.Fatalf("unexpected message: %+v", got)
	}
}

func TestOversizedMessageRejected(t *testing.T) {
	c1, c2 := net.Pipe()
	defer func() { _ = c1.Close(); _ = c2.Close() }()

	if err := WriteMessage(c1, make([]byte, MaxMessageSize+1)); err == nil {
		t.Fatal("expected WriteMessage to reject oversized payload")
	}

	go func() {
		var hdr [4]byte
		binary.BigEndian.PutUint32(hdr[:], MaxMessageSize+10)
		_, _ = c1.Write(hdr[:])
	}()

	if _, err := ReadMessage(c2); err == nil {
		t.Fatal("expected ReadMessage to reject oversized header")
	}
}

func TestParseMessageType(t *testing.T) {
	typ, err := ParseMessageType([]byte(`{"type":"ping"}`))
	if err != nil || typ != TypePing {
		t.Fatalf("got (%q, %v)", typ, err)
	}
	if _, err := ParseMessageType([]byte(`{}`)); err == nil {
		t.Fatal("expected error for missing type")
	}
	if _, err := ParseMessageType(bytes.Repeat([]byte("{"), 3)); err == nil {
		t.Fatal("expected error for invalid json")
	}
}

