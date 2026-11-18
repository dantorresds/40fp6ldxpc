// Package waengine conecta o número da loja como "aparelho conectado" do
// WhatsApp (whatsmeow) e roda dentro do próprio celular Android, sem servidor.
//
// Aqui não existe fila no servidor. O motor só entrega as mensagens recebidas ao app
// (evento "message") e oferece funções para responder com texto e foto e para
// baixar áudios/fotos do cliente. A inteligência (Gemini) fica no app Android.
//
// A API pública usa só tipos simples para poder ser exportada pelo gomobile.
package waengine

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"
)

const Version = "0.1.0"

// Estados mostrados no app.
const (
	StateStarting     = "starting"
	StateNeedsPairing = "needs_pairing"
	StatePairing      = "pairing"
	StateConnected    = "connected"
	StateDisconnected = "disconnected"
	StateLoggedOut    = "logged_out"
	StateBanned       = "banned"
	StateStopped      = "stopped"
)

// Tamanho máximo de mídia baixada para mandar à IA (o Gemini aceita até 20 MB inline).
const maxMediaBytes = 15 * 1024 * 1024

// Listener recebe eventos do motor.
// kind: "state", "qr", "pair_code", "message", "message_old", "from_me", "call", "log".
type Listener interface {
	OnEvent(kind string, payload string)
}

type Engine struct {
	dataDir    string
	deviceName string

	mu        sync.Mutex
	listener  Listener
	client    *whatsmeow.Client
	db        *sql.DB
	state     string
	detail    string
	pairCode  string
	cancel    context.CancelFunc
	running   bool
	sentCount int
	media     map[string]mediaRef // id da mensagem -> mídia para baixar depois
	mediaKeys []string
	// Envios do próprio motor: o eco deles (se vier) não é o dono assumindo.
	sentIDs  map[string]time.Time // id da mensagem -> quando saiu
	lastSend map[string]time.Time // usuário do chat (número ou LID) -> último envio do robô
}

type mediaRef struct {
	msg  whatsmeow.DownloadableMessage
	mime string
}

// IncomingMessage é o JSON do evento "message".
type IncomingMessage struct {
	ID        string `json:"id"`
	Chat      string `json:"chat"`     // JID da conversa (use para responder)
	Sender    string `json:"sender"`   // JID de quem mandou
	Phone     string `json:"phone"`    // número com DDI, quando o WhatsApp informa
	PushName  string `json:"pushName"` // nome que o cliente usa no WhatsApp
	IsGroup   bool   `json:"isGroup"`
	Kind      string `json:"kind"` // text, audio, image, video, document, sticker, location, contact, other
	Text      string `json:"text"` // texto ou legenda
	Mime      string `json:"mime"`
	Seconds   int    `json:"seconds"`
	Timestamp int64  `json:"timestamp"` // unix ms
}

// NewEngine cria o motor. dataDir guarda a sessão do WhatsApp (nunca sai do aparelho).
func NewEngine(dataDir, deviceName string) *Engine {
	return &Engine{
		dataDir:    dataDir,
		deviceName: deviceName,
		state:      StateStopped,
		media:      map[string]mediaRef{},
	}
}

func (e *Engine) SetListener(l Listener) {
	e.mu.Lock()
	e.listener = l
	e.mu.Unlock()
}

func (e *Engine) emit(kind string, payload any) {
	e.mu.Lock()
	l := e.listener
	e.mu.Unlock()
	if l == nil {
		return
	}
	var text string
	switch v := payload.(type) {
	case string:
		text = v
	default:
		raw, _ := json.Marshal(v)
		text = string(raw)
	}
	l.OnEvent(kind, text)
}

func (e *Engine) setState(state, detail string) {
	e.mu.Lock()
	changed := e.state != state || e.detail != detail
	e.state = state
	e.detail = detail
	if state != StatePairing {
		e.pairCode = ""
	}
	e.mu.Unlock()
	if changed {
		e.emit("state", map[string]string{"state": state, "detail": detail})
	}
}

func (e *Engine) ownPhone() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.client == nil || e.client.Store == nil || e.client.Store.ID == nil {
		return ""
	}
	return e.client.Store.ID.User
}

// StatusJSON devolve {"state","detail","phone","pairCode","sent","version","running"}.
func (e *Engine) StatusJSON() string {
	phone := e.ownPhone()
	e.mu.Lock()
	defer e.mu.Unlock()
	raw, _ := json.Marshal(map[string]any{
		"state":    e.state,
		"detail":   e.detail,
		"phone":    phone,
		"pairCode": e.pairCode,
		"sent":     e.sentCount,
		"version":  Version,
		"running":  e.running,
	})
	return string(raw)
}

// Start abre a sessão salva e conecta.
func (e *Engine) Start() error {
	e.mu.Lock()
	if e.running {
		e.mu.Unlock()
		return nil
	}
	e.mu.Unlock()

	if err := os.MkdirAll(filepath.Join(e.dataDir, "media"), 0o700); err != nil {
		return fmt.Errorf("não foi possível criar a pasta de dados: %w", err)
	}
	dbPath := filepath.Join(e.dataDir, "whatsapp-sessao.db")
	db, err := sql.Open("sqlite", "file:"+filepath.ToSlash(dbPath)+"?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return err
	}
	db.SetMaxOpenConns(1)

	store.SetOSInfo("Atendente IA", [3]uint32{0, 1, 0})

	ctx, cancel := context.WithCancel(context.Background())
	container := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)
	if err := container.Upgrade(ctx); err != nil {
		cancel()
		_ = db.Close()
		return fmt.Errorf("falha ao preparar a sessão do WhatsApp: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		cancel()
		_ = db.Close()
		return err
	}

	client := whatsmeow.NewClient(device, waLog.Noop)
	client.EnableAutoReconnect = true
	client.AddEventHandler(e.handleEvent)

	e.mu.Lock()
	e.db = db
	e.client = client
	e.cancel = cancel
	e.running = true
	e.mu.Unlock()

	e.setState(StateStarting, "")
	if client.Store.ID != nil {
		if err := client.Connect(); err != nil {
			e.setState(StateDisconnected, err.Error())
		}
	} else {
		e.setState(StateNeedsPairing, "")
	}
	go e.cleanupLoop(ctx)
	return nil
}

// Stop desconecta sem apagar a sessão.
func (e *Engine) Stop() {
	e.mu.Lock()
	cancel, client, db := e.cancel, e.client, e.db
	e.running = false
	e.cancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if client != nil {
		client.Disconnect()
	}
	if db != nil {
		_ = db.Close()
	}
	e.setState(StateStopped, "")
}

// StartPairing conecta um número novo. Com phone (DDI+DDD+número) devolve o
// código de 8 letras para digitar no WhatsApp; sem phone, emite eventos "qr".
func (e *Engine) StartPairing(phone string) (string, error) {
	e.mu.Lock()
	client := e.client
	running := e.running
	e.mu.Unlock()
	if client == nil || !running {
		return "", errors.New("o motor do WhatsApp não foi iniciado")
	}
	if client.Store.ID != nil {
		return "", errors.New("este aparelho já está conectado a um número; desconecte antes")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	client.Disconnect()
	qrChan, err := client.GetQRChannel(ctx)
	if err != nil {
		cancel()
		return "", err
	}
	if err := client.Connect(); err != nil {
		cancel()
		return "", err
	}

	digits := onlyDigits(phone)
	codeReady := make(chan struct{}, 1)
	go func() {
		defer cancel()
		for item := range qrChan {
			switch item.Event {
			case "code":
				if digits == "" {
					e.emit("qr", item.Code)
				}
				select {
				case codeReady <- struct{}{}:
				default:
				}
			case "success":
				e.setState(StateConnected, "")
				return
			case "timeout":
				e.setState(StateNeedsPairing, "O tempo para conectar acabou. Tente de novo.")
				return
			case "error":
				msg := "Falha ao conectar."
				if item.Error != nil {
					msg = item.Error.Error()
				}
				e.setState(StateNeedsPairing, msg)
				return
			}
		}
	}()

	select {
	case <-codeReady:
	case <-ctx.Done():
		return "", errors.New("o WhatsApp não respondeu a tempo")
	}

	if digits == "" {
		e.setState(StatePairing, "Leia o QR Code no WhatsApp da loja.")
		return "", nil
	}
	code, err := client.PairPhone(ctx, digits, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		return "", fmt.Errorf("não foi possível gerar o código: %w", err)
	}
	e.mu.Lock()
	e.pairCode = code
	e.state = "" // força reenviar o estado com o código
	e.mu.Unlock()
	e.emit("pair_code", code)
	e.setState(StatePairing, "Digite o código no WhatsApp: Aparelhos conectados › Conectar com número de telefone.")
	return code, nil
}

// Logout desconecta o número da loja deste aparelho (apaga a sessão).
func (e *Engine) Logout() error {
	client := e.getClient()
	if client == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := client.Logout(ctx)
	e.setState(StateNeedsPairing, "")
	return err
}

func (e *Engine) getClient() *whatsmeow.Client {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.client
}

func (e *Engine) connectedClient() (*whatsmeow.Client, error) {
	c := e.getClient()
	if c == nil || !c.IsConnected() || !c.IsLoggedIn() {
		return nil, errors.New("WhatsApp desconectado")
	}
	return c, nil
}

func (e *Engine) handleEvent(evt any) {
	switch v := evt.(type) {
	case *events.Connected:
		e.setState(StateConnected, "")
	case *events.PairSuccess:
		e.setState(StateConnected, "")
	case *events.Disconnected:
		e.setState(StateDisconnected, "Sem conexão com o WhatsApp. Tentando de novo…")
	case *events.LoggedOut:
		e.setState(StateLoggedOut, "O número foi desconectado pelo celular. Conecte de novo.")
	case *events.StreamReplaced:
		e.setState(StateDisconnected, "O WhatsApp da loja foi aberto em outro aparelho conectado.")
	case *events.TemporaryBan:
		e.setState(StateBanned, fmt.Sprintf("O WhatsApp bloqueou o número temporariamente (%s).", v.Expire))
	case *events.ConnectFailure:
		e.setState(StateDisconnected, v.Message)
	case *events.Message:
		e.onMessage(v)
	case *events.CallOffer:
		if !v.GroupJID.IsEmpty() {
			return
		}
		phone := phoneOf(v.CallCreator, v.CallCreatorAlt)
		if phone == "" {
			phone = phoneOf(v.From, types.EmptyJID)
		}
		if phone == "" {
			phone = e.pnForLID(v.CallCreator)
		}
		if phone == "" {
			phone = e.pnForLID(v.From)
		}
		e.emit("call", map[string]string{
			"from":   v.From.String(),
			"chat":   v.From.ToNonAD().String(),
			"phone":  phone,
			"callId": v.CallID,
		})
	}
}

func (e *Engine) onMessage(v *events.Message) {
	info := v.Info
	if info.Chat == types.StatusBroadcastJID || info.Chat.Server == types.NewsletterServer ||
		info.Chat.Server == types.BroadcastServer || v.Message == nil {
		return
	}
	if info.IsFromMe {
		// Mensagem que o dono mandou pelo próprio WhatsApp (outro aparelho):
		// o app pausa o robô nessa conversa. As enviadas por este motor não
		// voltam como evento.
		// Só conta texto/mídia de verdade, recente, vindo de OUTRO aparelho e que
		// não seja eco do que este motor acabou de mandar.
		client := e.getClient()
		if client == nil || client.Store.ID == nil || info.Sender.Device == client.Store.ID.Device ||
			time.Since(info.Timestamp) > 10*time.Minute || info.IsGroup {
			return
		}
		kind, text, ok := ownerKind(v.Message)
		if !ok {
			return
		}
		alt := types.EmptyJID
		if info.Chat.Server == types.HiddenUserServer {
			if pn := e.pnForLID(info.Chat); pn != "" {
				alt = types.NewJID(pn, types.DefaultUserServer)
			}
		}
		if e.ownEcho(info.ID, info.Chat, info.RecipientAlt, alt) {
			return
		}
		e.emit("from_me", map[string]string{"chat": info.Chat.String(), "id": info.ID, "kind": kind, "text": text,
			"device": fmt.Sprint(info.Sender.Device), "timestamp": fmt.Sprint(info.Timestamp.UnixMilli())})
		return
	}
	// Mensagens antigas (chegaram com o app desligado) não são respondidas, mas
	// vão para a memória da conversa (até 3 dias).
	if age := time.Since(info.Timestamp); age > 10*time.Minute {
		if age < 72*time.Hour && !info.IsGroup {
			kind, text := describe(v.Message)
			if kind != "" {
				e.emit("message_old", IncomingMessage{ID: info.ID, Chat: info.Chat.String(), Sender: info.Sender.String(),
					Phone: phoneOf(info.Sender, info.SenderAlt), PushName: info.PushName, Kind: kind, Text: text,
					Timestamp: info.Timestamp.UnixMilli()})
			}
		}
		return
	}
	m := v.Message
	phone := phoneOf(info.Sender, info.SenderAlt)
	if phone == "" {
		phone = e.pnForLID(info.Sender)
	}
	out := IncomingMessage{
		ID:        info.ID,
		Chat:      info.Chat.String(),
		Sender:    info.Sender.String(),
		Phone:     phone,
		PushName:  info.PushName,
		IsGroup:   info.IsGroup,
		Timestamp: info.Timestamp.UnixMilli(),
	}
	var dl whatsmeow.DownloadableMessage
	switch {
	case m.GetConversation() != "":
		out.Kind, out.Text = "text", m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		out.Kind, out.Text = "text", m.GetExtendedTextMessage().GetText()
	case m.GetAudioMessage() != nil:
		a := m.GetAudioMessage()
		out.Kind, out.Mime, out.Seconds = "audio", a.GetMimetype(), int(a.GetSeconds())
		dl = a
	case m.GetImageMessage() != nil:
		i := m.GetImageMessage()
		out.Kind, out.Mime, out.Text = "image", i.GetMimetype(), i.GetCaption()
		dl = i
	case m.GetVideoMessage() != nil:
		out.Kind, out.Text = "video", m.GetVideoMessage().GetCaption()
	case m.GetDocumentMessage() != nil:
		out.Kind, out.Text = "document", m.GetDocumentMessage().GetFileName()
	case m.GetStickerMessage() != nil:
		out.Kind = "sticker"
	case m.GetLocationMessage() != nil:
		l := m.GetLocationMessage()
		out.Kind = "location"
		out.Text = fmt.Sprintf("%s %s (%.6f, %.6f)", l.GetName(), l.GetAddress(), l.GetDegreesLatitude(), l.GetDegreesLongitude())
	case m.GetContactMessage() != nil:
		out.Kind, out.Text = "contact", m.GetContactMessage().GetDisplayName()
	default:
		return // reações, apagar/editar, chaves de grupo etc. não pedem resposta
	}
	if dl != nil {
		e.mu.Lock()
		e.media[info.ID] = mediaRef{msg: dl, mime: out.Mime}
		e.mediaKeys = append(e.mediaKeys, info.ID)
		if len(e.mediaKeys) > 300 {
			delete(e.media, e.mediaKeys[0])
			e.mediaKeys = e.mediaKeys[1:]
		}
		e.mu.Unlock()
	}
	e.emit("message", out)
}

// describe resume uma mensagem em tipo e texto (sem baixar mídia). Tipo vazio =
// não é conteúdo de conversa (reação, apagar, chaves…).
func describe(m *waE2E.Message) (string, string) {
	switch {
	case m == nil:
		return "", ""
	case m.GetConversation() != "":
		return "text", m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		return "text", m.GetExtendedTextMessage().GetText()
	case m.GetAudioMessage() != nil:
		return "audio", ""
	case m.GetImageMessage() != nil:
		return "image", m.GetImageMessage().GetCaption()
	case m.GetVideoMessage() != nil:
		return "video", m.GetVideoMessage().GetCaption()
	case m.GetDocumentMessage() != nil:
		return "document", m.GetDocumentMessage().GetFileName()
	case m.GetStickerMessage() != nil:
		return "sticker", ""
	case m.GetLocationMessage() != nil:
		return "location", m.GetLocationMessage().GetName()
	case m.GetContactMessage() != nil:
		return "contact", m.GetContactMessage().GetDisplayName()
	}
	return "", ""
}

// DownloadMedia baixa o áudio/foto de uma mensagem recebida e devolve o
// caminho do arquivo salvo (apagado sozinho depois de 1 hora).
func (e *Engine) DownloadMedia(messageID string) (string, error) {
	client, err := e.connectedClient()
	if err != nil {
		return "", err
	}
	e.mu.Lock()
	ref, ok := e.media[messageID]
	e.mu.Unlock()
	if !ok {
		return "", errors.New("mídia não encontrada (mensagem antiga)")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	data, err := client.Download(ctx, ref.msg)
	if err != nil {
		return "", fmt.Errorf("falha ao baixar a mídia: %w", err)
	}
	if len(data) > maxMediaBytes {
		return "", errors.New("arquivo grande demais para a IA")
	}
	ext := ".bin"
	switch {
	case strings.HasPrefix(ref.mime, "audio/ogg"):
		ext = ".ogg"
	case strings.HasPrefix(ref.mime, "audio/mp4"), strings.HasPrefix(ref.mime, "audio/aac"):
		ext = ".m4a"
	case strings.HasPrefix(ref.mime, "audio/mpeg"):
		ext = ".mp3"
	case strings.HasPrefix(ref.mime, "image/jpeg"):
		ext = ".jpg"
	case strings.HasPrefix(ref.mime, "image/png"):
		ext = ".png"
	case strings.HasPrefix(ref.mime, "image/webp"):
		ext = ".webp"
	}
	path := filepath.Join(e.dataDir, "media", safeName(messageID)+ext)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

// MarkRead marca a mensagem como lida (tiques azuis).
func (e *Engine) MarkRead(chat, sender, messageID string) error {
	client, err := e.connectedClient()
	if err != nil {
		return err
	}
	chatJID, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	senderJID, _ := types.ParseJID(sender)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return client.MarkRead(ctx, []types.MessageID{messageID}, time.Now(), chatJID, senderJID)
}

// SendText responde com texto. Mostra "digitando…" por um tempo proporcional
// ao tamanho, para parecer uso humano.
func (e *Engine) SendText(chat, text string) error {
	return e.SendTextTyping(chat, text, 0)
}

// SendTextTyping é o SendText com o tempo de "digitando…" escolhido pelo app
// (em milissegundos). Com typingMs <= 0, usa o tempo proporcional ao tamanho.
// Serve para os pedaços de uma resposta fracionada saírem no intervalo configurado.
func (e *Engine) SendTextTyping(chat, text string, typingMs int) error {
	client, err := e.connectedClient()
	if err != nil {
		return err
	}
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	typing := time.Duration(1200+min(len(text)*35, 6000)+rand.Intn(1200)) * time.Millisecond
	if typingMs > 0 {
		typing = time.Duration(min(typingMs, 60_000)) * time.Millisecond
	}
	_ = client.SendChatPresence(ctx, jid, types.ChatPresenceComposing, types.ChatPresenceMediaText)
	sleepCtx(ctx, typing)
	_ = client.SendChatPresence(ctx, jid, types.ChatPresencePaused, types.ChatPresenceMediaText)
	e.noteSending(jid)
	resp, err := client.SendMessage(ctx, jid, &waE2E.Message{Conversation: proto.String(text)})
	if err != nil {
		return fmt.Errorf("falha ao enviar: %w", err)
	}
	e.markSent(jid, resp.ID)
	return nil
}

// SetPresence mostra ao cliente "digitando…" (mode "typing"), "gravando áudio…"
// ("recording") ou tira o aviso ("paused"). Usado enquanto a IA pensa ou a voz
// é gerada. Erros são ignorados: é só um aviso visual.
func (e *Engine) SetPresence(chat, mode string) {
	client, err := e.connectedClient()
	if err != nil {
		return
	}
	jid, err := types.ParseJID(chat)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	switch mode {
	case "typing":
		_ = client.SendChatPresence(ctx, jid, types.ChatPresenceComposing, types.ChatPresenceMediaText)
	case "recording":
		_ = client.SendChatPresence(ctx, jid, types.ChatPresenceComposing, types.ChatPresenceMediaAudio)
	default:
		_ = client.SendChatPresence(ctx, jid, types.ChatPresencePaused, types.ChatPresenceMediaText)
	}
}

// SendImage envia uma foto (arquivo local do app) com legenda opcional.
func (e *Engine) SendImage(chat, filePath, caption string) error {
	client, err := e.connectedClient()
	if err != nil {
		return err
	}
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(filePath)
	if err != nil {
		return fmt.Errorf("foto não encontrada: %w", err)
	}
	mime := http.DetectContentType(data)
	if !strings.HasPrefix(mime, "image/") {
		return errors.New("o arquivo não é uma imagem")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	up, err := client.Upload(ctx, data, whatsmeow.MediaImage)
	if err != nil {
		return fmt.Errorf("falha ao subir a foto: %w", err)
	}
	msg := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		Caption:       proto.String(caption),
		Mimetype:      proto.String(mime),
		URL:           proto.String(up.URL),
		DirectPath:    proto.String(up.DirectPath),
		MediaKey:      up.MediaKey,
		FileEncSHA256: up.FileEncSHA256,
		FileSHA256:    up.FileSHA256,
		FileLength:    proto.Uint64(up.FileLength),
	}}
	e.noteSending(jid)
	resp, err := client.SendMessage(ctx, jid, msg)
	if err != nil {
		return fmt.Errorf("falha ao enviar a foto: %w", err)
	}
	e.markSent(jid, resp.ID)
	return nil
}

// SendVoice envia um áudio OGG/Opus como mensagem de voz (o "microfone azul"),
// mostrando "gravando áudio…" antes, por um tempo parecido com a duração.
func (e *Engine) SendVoice(chat, filePath string, seconds int) error {
	client, err := e.connectedClient()
	if err != nil {
		return err
	}
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(filePath)
	if err != nil {
		return fmt.Errorf("áudio não encontrado: %w", err)
	}
	if len(data) < 4 || string(data[:4]) != "OggS" {
		return errors.New("o áudio precisa ser OGG/Opus")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	recording := time.Duration(min(max(seconds, 1)*700, 8000)+rand.Intn(800)) * time.Millisecond
	_ = client.SendChatPresence(ctx, jid, types.ChatPresenceComposing, types.ChatPresenceMediaAudio)
	sleepCtx(ctx, recording)
	_ = client.SendChatPresence(ctx, jid, types.ChatPresencePaused, types.ChatPresenceMediaAudio)
	up, err := client.Upload(ctx, data, whatsmeow.MediaAudio)
	if err != nil {
		return fmt.Errorf("falha ao subir o áudio: %w", err)
	}
	msg := &waE2E.Message{AudioMessage: &waE2E.AudioMessage{
		Mimetype:      proto.String("audio/ogg; codecs=opus"),
		PTT:           proto.Bool(true),
		Seconds:       proto.Uint32(uint32(max(seconds, 1))),
		URL:           proto.String(up.URL),
		DirectPath:    proto.String(up.DirectPath),
		MediaKey:      up.MediaKey,
		FileEncSHA256: up.FileEncSHA256,
		FileSHA256:    up.FileSHA256,
		FileLength:    proto.Uint64(up.FileLength),
	}}
	e.noteSending(jid)
	resp, err := client.SendMessage(ctx, jid, msg)
	if err != nil {
		return fmt.Errorf("falha ao enviar o áudio: %w", err)
	}
	e.markSent(jid, resp.ID)
	return nil
}

// RejectCall recusa uma chamada de voz/vídeo recebida.
func (e *Engine) RejectCall(from, callID string) error {
	client, err := e.connectedClient()
	if err != nil {
		return err
	}
	jid, err := types.ParseJID(from)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return client.RejectCall(ctx, jid, callID)
}

// noteSending marca que o robô está enviando nesse chat (antes do envio, para
// o caso de o eco chegar antes de o SendMessage voltar).
func (e *Engine) noteSending(jid types.JID) {
	e.mu.Lock()
	if e.lastSend == nil {
		e.lastSend = map[string]time.Time{}
	}
	e.lastSend[jid.User] = time.Now()
	e.mu.Unlock()
}

func (e *Engine) markSent(jid types.JID, id types.MessageID) {
	now := time.Now()
	e.mu.Lock()
	defer e.mu.Unlock()
	e.sentCount++
	if e.sentIDs == nil {
		e.sentIDs = map[string]time.Time{}
	}
	if e.lastSend == nil {
		e.lastSend = map[string]time.Time{}
	}
	e.lastSend[jid.User] = now
	if id != "" {
		e.sentIDs[id] = now
	}
	if len(e.sentIDs) > 500 {
		for k, t := range e.sentIDs {
			if now.Sub(t) > time.Hour {
				delete(e.sentIDs, k)
			}
		}
	}
}

// ownEcho diz se a mensagem "enviada por mim" é na verdade um envio do próprio
// motor: mesmo ID, ou chegou poucos segundos depois de o robô enviar no chat.
func (e *Engine) ownEcho(id string, chats ...types.JID) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, ok := e.sentIDs[id]; ok {
		return true
	}
	for _, c := range chats {
		if c.IsEmpty() {
			continue
		}
		if t, ok := e.lastSend[c.User]; ok && time.Since(t) < ownEchoWindow {
			return true
		}
	}
	return false
}

// ownEchoWindow: mensagem "minha" que chega até esse tempo depois de um envio do
// robô no mesmo chat é tratada como eco, não como o dono assumindo.
const ownEchoWindow = 6 * time.Second

// ownerKind diz se uma mensagem enviada pelo dono conta como "assumir a
// conversa": só texto de verdade ou mídia. Reações, edições, enquetes,
// mensagens de protocolo, figurinhas etc. não pausam o robô.
func ownerKind(m *waE2E.Message) (kind, text string, ok bool) {
	kind, text = describe(m)
	switch kind {
	case "text":
		return kind, text, strings.TrimSpace(text) != ""
	case "audio", "image", "video", "document", "location", "contact":
		return kind, text, true
	}
	return kind, text, false
}

// cleanupLoop apaga mídias baixadas há mais de 1 hora.
func (e *Engine) cleanupLoop(ctx context.Context) {
	for sleepCtx(ctx, 15*time.Minute) {
		dir := filepath.Join(e.dataDir, "media")
		entries, _ := os.ReadDir(dir)
		for _, en := range entries {
			if fi, err := en.Info(); err == nil && time.Since(fi.ModTime()) > time.Hour {
				_ = os.Remove(filepath.Join(dir, en.Name()))
			}
		}
	}
}

// pnForLID descobre o número de um contato com número oculto (LID) no banco da sessão.
func (e *Engine) pnForLID(jid types.JID) string {
	if jid.Server != types.HiddenUserServer {
		return ""
	}
	client := e.getClient()
	if client == nil || client.Store == nil || client.Store.LIDs == nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if pn, err := client.Store.LIDs.GetPNForLID(ctx, jid.ToNonAD()); err == nil && pn.Server == types.DefaultUserServer {
		return pn.User
	}
	return ""
}

// phoneOf devolve o número (só dígitos) de um JID de telefone, tentando o
// endereço alternativo quando o principal é um LID (identificador opaco).
func phoneOf(main, alt types.JID) string {
	if main.Server == types.DefaultUserServer {
		return main.User
	}
	if alt.Server == types.DefaultUserServer {
		return alt.User
	}
	return ""
}

func safeName(s string) string {
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		}
	}
	if b.Len() == 0 {
		return fmt.Sprint(time.Now().UnixNano())
	}
	return b.String()
}

func onlyDigits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
