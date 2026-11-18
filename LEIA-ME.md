# Atendente IA para WhatsApp (Android)

App Android de código aberto que responde as conversas do WhatsApp de um
negócio com o **Gemini**, rodando **no próprio celular, em segundo plano, sem
servidor**. O WhatsApp é conectado pela biblioteca [whatsmeow](https://github.com/tulir/whatsmeow)
(em Go, empacotada com o gomobile) e a IA é chamada direto do aparelho.

> Aviso: este código é fornecido "como está", sem garantias de qualquer tipo.
> Automatizar o WhatsApp pode violar os termos de uso do serviço e levar ao
> bloqueio do número. Use por sua conta e risco.

## O que o app faz

- Guarda as **instruções e preferências** do negócio no celular, em até 3 personas
  (atendente, vendedor, suporte…) com um orquestrador que escolhe qual atende.
- **Entende áudios e fotos** do cliente (o Gemini ouve o áudio direto).
- **Manda fotos** cadastradas e **links** (catálogo, redes sociais, localização…).
- **Passa a conversa para a equipe** quando o cliente pede um atendente, e pausa
  sozinho quando você responde pelo seu WhatsApp.
- **Lê sites antes de responder** (aba de fontes): HTML direto ou Jina Reader para
  páginas montadas com JavaScript; cache de 3 minutos.
- **Responde áudio com áudio** (Gemini TTS), humaniza as respostas (mensagens curtas,
  "digitando…") e retoma conversas paradas (follow-up).
- Modo palavra-chave, regras para chamadas, aba de teste e assistente de configuração
  por entrevista. Modelos de prompt em `docs/prompts/`.
- Vendas pelo WhatsApp (desligado por padrão): ver [docs/VENDAS.md](docs/VENDAS.md).

## Como funciona

```
WhatsApp (celular)  ──"aparelho conectado"──►  Motor Go (whatsmeow, dentro do app)
                                                  │ evento "message" (texto/áudio/foto)
                                                  ▼
                         AtendenteService (serviço em primeiro plano, Kotlin)
                           ├─ junta mensagens seguidas
                           ├─ manda texto/áudio/foto ao Gemini com as instruções
                           ├─ Gemini devolve JSON {reply, photos, handoff, ...}
                           └─ responde: texto, fotos, ou passa para a equipe
```

O app entra como aparelho conectado do número (igual ao WhatsApp Web). A sessão
fica no app; as chamadas vão direto do celular para a API do Gemini. Nenhum
servidor é necessário.

## Estrutura de pastas

| Pasta | O que é |
| --- | --- |
| `engine/` | Motor em Go (whatsmeow). Vira `waengine.aar` com o gomobile. |
| `android/` | App Android (Kotlin + Jetpack Compose). |
| `android/app/src/main/java/com/atendenteia/app/` | `AtendenteService` (segundo plano), `Brain` (prompt e regras), `Gemini` (API), `Store` (configuração e histórico), `MainActivity` e telas. |
| `android/app/src/test/` | Testes unitários (JUnit). |
| `docs/` | Documentação e modelos de prompt. |
| `scripts/build.ps1` | Gera o motor e o APK no Windows. |
| `.github/workflows/build.yml` | Gera o APK no GitHub Actions (manual). |

## Como compilar

Requisitos: JDK 17, Android SDK + NDK 27.2.12479018, Go e
[gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) (`gomobile init`).

```powershell
# Windows: testa o motor, gera o waengine.aar e o APK de debug
./scripts/build.ps1
./scripts/build.ps1 -SkipEngine   # reaproveita o .aar já gerado
```

Passo a passo manual:

```bash
cd engine
go test ./...
gomobile bind -target="android/arm64,android/arm" -androidapi 23 -ldflags="-s -w" \
  -javapkg com.atendenteia.wa -o out/waengine.aar ./waengine
cp out/waengine.aar ../android/app/libs/waengine.aar

cd ../android
./gradlew testDebugUnitTest assembleDebug
```

Crie `android/local.properties` com `sdk.dir=/caminho/do/Android/Sdk` (o arquivo
não vai para o Git). O APK sai em `android/app/build/outputs/apk/`.

## Como configurar

1. Instale o APK no celular.
2. Crie uma chave da API do Gemini em <https://aistudio.google.com/apikey> e **cole-a
   no app** (a chave fica criptografada no aparelho). Ela nunca deve ser colocada no
   código nem no repositório.
3. **Início › Ligar o motor do WhatsApp**, informe o número e toque em **Gerar código**;
   no WhatsApp vá em Aparelhos conectados › Conectar com número de telefone e digite o código.
4. Preencha as instruções do negócio (ou use o assistente de configuração) e teste na aba Testar.

## Segurança e privacidade

- Não coloque chaves de API, sessão do WhatsApp nem números de clientes no repositório.
- A sessão do WhatsApp e as chaves ficam só no celular (SharedPreferences criptografadas).

## Licença e créditos

O motor usa a biblioteca whatsmeow (MPL-2.0). Veja as licenças das dependências
antes de redistribuir.
