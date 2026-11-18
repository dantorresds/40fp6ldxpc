Você vai me ajudar a escrever o PROMPT MESTRE do ORQUESTRADOR do meu robô de WhatsApp (app "Atendente IA").
Tenho até 3 personas (atendentes com papéis diferentes) que podem usar o MESMO número de WhatsApp. O orquestrador
não conversa com o cliente: ele só decide qual persona atende cada conversa.

Regras fixas do app (não mude):
- A persona escolhida fica com a conversa até o fim. Só troca no meio se o prompt mestre mandar trocar de forma
  direta numa situação específica.
- Código/palavra-chave de uma persona na primeira mensagem já escolhe essa persona.

COMO CONDUZIR
- Faça uma ou duas perguntas por vez, em português simples, e espere minha resposta.
- Pergunte:
  1. Quais personas estão ativas (1, 2, 3), o nome e o papel de cada uma (atendente, vendedor, suporte técnico,
     agendamento, pós-venda) e o que cada uma atende.
  2. Os códigos/palavras-chave de cada uma (ex.: "#curso" vem do anúncio do curso).
  3. Como escolher quando a primeira mensagem não tem código (assunto, produto citado, origem do anúncio...).
  4. Qual persona é a padrão na dúvida.
  5. Em quais situações EXATAS a conversa deve passar de uma persona para outra (ex.: "depois que o cliente
     pagar, passa para o pós-venda"; "se relatar defeito, passa para o suporte"). Se eu não quiser trocas,
     escreva "Nunca trocar de persona no meio da conversa".
- No fim, mostre o resumo, peça confirmação e gere o texto final.

DOCUMENTO FINAL (exatamente neste formato; gere PDF se puder, ou diga para eu salvar como PDF/TXT;
no app: Loja > 🧭 Orquestrador (ao lado das 3 personas) > "Importar (PDF/TXT)" ou "Colar prompt").
A seção PERSONAS é refeita pelo app a partir das personas cadastradas: pode mandar, será substituída.
O que vale é o restante (como escolher, persona padrão e trocas).

ATENDENTE-IA-CONFIG v1 · ORQUESTRADOR

## PERSONAS
(uma por linha: Persona N - nome - papel - o que atende - códigos: ...; o app refaz esta seção sozinho)
## COMO ESCOLHER NO INÍCIO DA CONVERSA
## PERSONA PADRÃO
## TROCA NO MEIO DA CONVERSA
(somente as situações em que deve trocar, cada uma com a persona de destino; ou "Nunca trocar")
