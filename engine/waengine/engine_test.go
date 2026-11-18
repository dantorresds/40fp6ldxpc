package waengine

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
)

func TestPhoneOf(t *testing.T) {
	phone := types.NewJID("5511999990000", types.DefaultUserServer)
	lid := types.NewJID("123456789", types.HiddenUserServer)
	if got := phoneOf(phone, types.EmptyJID); got != "5511999990000" {
		t.Fatalf("telefone direto: %q", got)
	}
	if got := phoneOf(lid, phone); got != "5511999990000" {
		t.Fatalf("telefone alternativo: %q", got)
	}
	if got := phoneOf(lid, types.EmptyJID); got != "" {
		t.Fatalf("LID sem alternativo deveria ficar vazio: %q", got)
	}
}

func TestSafeName(t *testing.T) {
	if got := safeName("3EB0../../x"); got != "3EB0x" {
		t.Fatalf("safeName: %q", got)
	}
}

func TestOnlyDigits(t *testing.T) {
	if got := onlyDigits("+55 (11) 99999-0000"); got != "5511999990000" {
		t.Fatalf("onlyDigits: %q", got)
	}
}

func TestOwnerKindIgnoraTecnicas(t *testing.T) {
	txt := "oi, tudo bem?"
	if _, _, ok := ownerKind(&waE2E.Message{Conversation: &txt}); !ok {
		t.Fatal("texto do dono deveria pausar")
	}
	vazio := "   "
	if _, _, ok := ownerKind(&waE2E.Message{Conversation: &vazio}); ok {
		t.Fatal("texto vazio não deveria pausar")
	}
	if _, _, ok := ownerKind(&waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{}}); ok {
		t.Fatal("reação não deveria pausar")
	}
	if _, _, ok := ownerKind(&waE2E.Message{ProtocolMessage: &waE2E.ProtocolMessage{}}); ok {
		t.Fatal("protocolo não deveria pausar")
	}
	if _, _, ok := ownerKind(&waE2E.Message{StickerMessage: &waE2E.StickerMessage{}}); ok {
		t.Fatal("figurinha não deveria pausar")
	}
}

func TestOwnEcho(t *testing.T) {
	e := &Engine{}
	chat := types.NewJID("5511999990000", types.DefaultUserServer)
	other := types.NewJID("5511888880000", types.DefaultUserServer)
	e.markSent(chat, "ABC")
	if !e.ownEcho("ABC", other) {
		t.Fatal("ID enviado pelo motor é eco")
	}
	if !e.ownEcho("XYZ", chat) {
		t.Fatal("logo após o envio no mesmo chat é eco")
	}
	if e.ownEcho("XYZ", other) {
		t.Fatal("outro chat não é eco")
	}
}
