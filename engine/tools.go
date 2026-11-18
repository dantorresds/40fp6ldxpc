//go:build tools

// Mantém o golang.org/x/mobile no go.mod: o `gomobile bind` precisa dele
// para gerar a biblioteca Android (AAR) do motor.
package tools

import _ "golang.org/x/mobile/bind"
