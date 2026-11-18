# Vendas pelo WhatsApp (aba Vendas)

Cada produto tem um tipo:

| Tipo | O que a IA faz | Precisa de |
| --- | --- | --- |
| Infoproduto próprio | pede um e-mail; o app gera Pix (e link de cartão com taxa), confere o pagamento a cada 20 s e, aprovado, manda o **link de entrega** salvo no produto | token do Mercado Pago + link de entrega (Drive "qualquer pessoa com o link", Dropbox…) |
| Infoproduto na página de vendas | tira dúvidas e manda o link (Hotmart, Kiwify, site) | só o link |
| Produto físico de afiliado | indica e manda o link de afiliado | só o link |

Tudo começa desligado (aba Vendas › 1. Ligar vendas).

## Mercado Pago (infoproduto próprio)

1. mercadopago.com.br/developers › Suas integrações › Criar aplicação (ex.: "Atendente IA").
2. Credenciais de produção › copie o **Access Token** (`APP_USR-…`).
3. App › Vendas › 2 › cole, Salvar, Testar.
4. A conta precisa ter **chave Pix** cadastrada no Mercado Pago.
5. Perdeu o celular? Revogue o token nessa aplicação e gere outro.

O token fica criptografado no celular (AndroidKeyStore). A IA nunca vê token,
valores ou links de entrega: o preço vem do cadastro do produto.

## Link de entrega (padrão, sem Google Cloud)

1. No Google Drive, clique no arquivo › Compartilhar › Acesso geral: **Qualquer pessoa com o link** (Leitor).
   Se quiser, em ⚙️ desmarque "Leitores podem baixar, imprimir e copiar".
2. App › Vendas › 4 › tipo "Infoproduto próprio" › nome, preço e o link › Cadastrar produto.

O link só vai para o cliente depois do pagamento aprovado. Quem tiver o link consegue abrir:
se ele vazar, gere outro link (ou outro arquivo) e atualize o produto.

## Arquivo privado no Google Drive (opcional, avançado) — uma vez só

Só para liberar o arquivo ao Gmail de quem pagou (aba Vendas › 3, chave "Liberar só para o Gmail").

O app usa o escopo `drive.file`: só enxerga os arquivos que ele mesmo enviou.

1. console.cloud.google.com › criar projeto (ex.: "Atendente IA").
2. APIs e serviços › Biblioteca › **Google Drive API** › Ativar.
3. APIs e serviços › Tela de consentimento OAuth (Google Auth Platform):
   tipo **Externo**, nome do app, seu e-mail; em Público, **Publicar app**
   (com só o escopo `drive.file` não precisa de verificação; em "Teste" o
   acesso expira a cada 7 dias).
4. Credenciais › Criar credenciais › **ID do cliente OAuth** › tipo **Android**:
   - Nome do pacote: `com.atendenteia.app`
   - SHA-1: o da chave com que você assina o APK (`keytool -list -v -keystore sua.keystore`)
   - Usando também o **Atendente IA 2**, crie no mesmo projeto um segundo ID do cliente
     OAuth tipo Android, com o pacote `com.atendenteia.app.dois` e o mesmo SHA-1.
     O Google reconhece cada app pelo pacote, então cada cópia precisa do seu cadastro.
     (o SHA-1 também aparece na aba Vendas › 3).
5. App › Vendas › 3 › Conectar Google Drive › escolha a conta e autorize.

## Como a entrega privada protege o arquivo

- O arquivo sobe **privado** para o seu Drive.
- Pagamento aprovado (valor e código do pedido conferidos) → o app dá acesso de
  leitura só ao Gmail do comprador e manda o link. Outras pessoas veem
  "Você precisa de acesso".
- Opção "Impedir download": o comprador só lê no Drive (sem baixar/imprimir/copiar).
- E-mail que não é conta Google: o app avisa o cliente e pede um Gmail.
- "Não recebi": o app reenvia só para o mesmo número que comprou.
