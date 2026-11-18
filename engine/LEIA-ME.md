# Motor do WhatsApp (Go)

Conecta o número da loja como aparelho conectado (whatsmeow) e expõe uma API
simples para o app Android, gerada pelo `gomobile` como `waengine.aar`
(pacote Java `com.atendenteia.wa.waengine`).

## API (Java/Kotlin)

```kotlin
val e = Waengine.newEngine(dataDir, "Samsung A15")
e.setListener { kind, payload -> … }   // state, qr, pair_code, message, from_me, call
e.start(); e.startPairing("5511999998888")  // devolve o código de 8 letras
e.sendText(chat, "Olá!")                 // com "digitando…"
e.sendImage(chat, "/caminho/foto.jpg", "legenda")
val path = e.downloadMedia(messageId)    // áudio/foto recebido
e.markRead(chat, sender, messageId); e.rejectCall(from, callId)
e.statusJSON(); e.logout(); e.stop()
```

Evento `message` (JSON): `id, chat, sender, phone, pushName, isGroup, kind
(text|audio|image|video|document|sticker|location|contact), text, mime, seconds, timestamp`.
Mensagens com mais de 10 minutos (sincronização ao reconectar) são ignoradas.

## Testar e gerar o AAR

```powershell
go test ./...
gomobile bind -target="android/arm64,android/arm" -androidapi 23 -ldflags="-s -w" -javapkg com.atendenteia.wa -o out/waengine.aar ./waengine
```

Precisa de `ANDROID_HOME` e `ANDROID_NDK_HOME` (NDK 27.2.12479018). O
`scripts/build.ps1` da raiz faz tudo.
