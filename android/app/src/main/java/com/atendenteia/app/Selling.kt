package com.atendenteia.app

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Vendedor de WhatsApp um a um: técnicas no prompt, etiquetas do funil e
 * cadência de follow-up para recuperar a venda sem parecer robô nem insistente.
 */
object Selling {

    fun promptSection(p: Persona) = """
## Como vender (você é um vendedor experiente de WhatsApp, conversa um a um)
- Crie conexão antes de oferecer: use o nome do cliente, retome o que ele disse, mostre interesse genuíno.
- Descubra a necessidade com 2 a 4 perguntas abertas e curtas, uma por mensagem (o que procura, para quando, para quem, o que é importante). Não faça interrogatório.
- Apresente a solução ligada ao que o cliente contou. Diga o preço com clareza quando perguntarem; nunca esconda.
- Termine com um convite pequeno e fácil ("quer que eu te mande o link?", "separo pra você?"), nunca com "fico à disposição".
- Objeção: acolha ("entendo"), pergunte para entender o que pesa de verdade, trate a objeção real com valor ou prova, e faça um novo convite pequeno.
  • "Tá caro": mostre o valor (o que resolve, custo por dia, parcelamento se houver). Não dê desconto por reflexo.
  • "Vou pensar": pergunte com leveza o que ficou pesando mais (valor, momento ou alguma dúvida).
  • Confiança: use só provas reais das instruções (depoimentos, garantia, tempo de loja).
  • Precisa consultar alguém: ofereça um resumo curto para ele mostrar.
  • Momento: combine uma data para retomar e preencha "followUpAt".
- Ofertas que você pode usar (nada além disto; sem urgência ou escassez falsa): ${p.offers.ifBlank { "nenhuma — não ofereça desconto, bônus nem prazo especial" }}.
- Se o cliente disser que não quer, respeite, agradeça e não insista.
""".trim()

    fun funnelSection() = """
## Etiquetas do funil (preencha "lead" em toda resposta)
- temperature: "quente" (pergunta preço, prazo, pagamento, entrega, quer fechar), "morno" (interessado mas indeciso, "vou pensar"), "frio" (pouco interesse ou parou de responder).
- stage: "novo" (primeiro contato), "conversa" (tirando dúvidas), "interessado" (já viu preço/proposta), "pagamento" (vai pagar ou Pix gerado), "comprou", "posvenda" (já comprou e voltou), "perdido" (desistiu).
- objection: o que trava a compra: "preco", "confianca", "momento", "comparando", "consultar", "duvida", "outra" ou "".
- followUpAt: se o cliente pedir para ser chamado depois ("me chama sexta"), a data e hora no formato dd/MM HH:mm; senão "".
- optOut: true só se o cliente pedir para não receber mais mensagens.
- awaitingReply: true se a conversa ficou em aberto (você perguntou algo ou fez uma oferta e a compra não foi concluída); false se encerrou.
""".trim()

    /** Pedido explícito para parar de receber mensagens. */
    fun isOptOut(text: String): Boolean {
        val n = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        return Regex("\\b(para de me mandar|pare de me mandar|nao me mande mais|nao quero mais receber|me tira da lista|nao me chame mais|parem de mandar)\\b").containsMatchIn(n)
    }

    /** Espera (min) antes de cada toque, contada do toque anterior: 30 min, dia 1, dia 3, dia 6. */
    fun cadence(stage: String, firstMinutes: Int): List<Int> =
        if (Funnel.isLate(stage)) listOf(firstMinutes, 24 * 60, 2 * 24 * 60, 3 * 24 * 60)
        else listOf(firstMinutes, 24 * 60)

    /** Objetivo de cada toque, dito à IA na instrução interna. */
    fun instruction(step: Int, total: Int, stage: String, objection: String, kind: String, silentFor: String): String {
        val goal = when {
            kind == "combinado" -> "O cliente pediu para ser chamado agora. Retome com naturalidade o que vocês combinaram."
            stage == "pagamento" && step == 0 -> "O cliente ia pagar e não confirmou. Pergunte com leveza se deu certo ou se ficou alguma dúvida no pagamento."
            step == 0 -> "Retome a conversa de onde parou, com algo útil (uma informação, uma sugestão ou uma pergunta simples ligada ao que ele queria). Não pergunte \"conseguiu ver?\"."
            step == total - 1 && total > 2 -> "Última mensagem: despeça-se com educação, diga que não vai mais insistir e que ele pode chamar quando quiser. Sem cobrança, sem culpa."
            step == 2 -> "Descubra o que travou: pergunte com leveza o que pesa mais agora (o valor, o momento ou alguma dúvida)." +
                if (objection.isNotBlank()) " A objeção percebida foi: ${Funnel.OBJECTIONS[objection]}. Trate-a." else ""
            else -> "Traga um elemento novo que ajude a decidir (um benefício, uma dúvida comum respondida, uma opinião de cliente se houver nas instruções) e faça um convite pequeno."
        }
        return """O cliente não responde há $silentFor. Escreva a mensagem de retomada nº ${step + 1} de $total.
Objetivo: $goal
Regras: curta (1 a 2 mensagens), natural como um vendedor humano, use o contexto real da conversa, não repita nada que já foi dito, nada de "passando para lembrar", sem pressão e sem urgência falsa. Preencha "lead" normalmente."""
    }

    fun humanDuration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 90 -> "$min minutos"
            min < 36 * 60 -> "${min / 60} horas"
            else -> "${min / 1440} dias"
        }
    }

    /** Ajusta o horário para dentro da janela permitida (com alguns minutos aleatórios). */
    fun fitWindow(at: Long, p: Persona): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = at + Random.nextLong(0, 6 * 60_000) }
        repeat(8) {
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val sunday = cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            if (!sunday || p.followSunday) {
                if (h >= p.followStartHour && h < p.followEndHour) return cal.timeInMillis
                if (h < p.followStartHour) {
                    cal.set(Calendar.HOUR_OF_DAY, p.followStartHour); cal.set(Calendar.MINUTE, Random.nextInt(5, 50)); return cal.timeInMillis
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, p.followStartHour); cal.set(Calendar.MINUTE, Random.nextInt(5, 50))
            if (!(cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && !p.followSunday)) return cal.timeInMillis
        }
        return cal.timeInMillis
    }

    /** "dd/MM HH:mm" → ms (no futuro; ano corrente ou o próximo). */
    fun parseWhen(s: String): Long? = runCatching {
        val now = Calendar.getInstance()
        val d = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).parse(s.trim()) ?: return null
        val cal = Calendar.getInstance().apply { time = d; set(Calendar.YEAR, now.get(Calendar.YEAR)) }
        if (cal.timeInMillis < now.timeInMillis - 3_600_000) cal.add(Calendar.YEAR, 1)
        cal.timeInMillis
    }.getOrNull()
}
