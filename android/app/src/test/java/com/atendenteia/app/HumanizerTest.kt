package com.atendenteia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanizerTest {

    @Test fun usaPartsQuandoVierem() {
        val out = Humanizer.finish(listOf("Oi! Tudo bem?", "  ", "A pronta entrega abre às 14h."), "", 1)
        assertEquals(listOf("Oi! Tudo bem?", "A pronta entrega abre às 14h."), out)
    }

    @Test fun respostaCurtaNaoEFracionada() {
        assertEquals(listOf("Temos sim, R$ 12,00 cada."), Humanizer.finish(emptyList(), "Temos sim, R$ 12,00 cada.", 1))
    }

    @Test fun respostaLongaViraVariasMensagens() {
        val longa = "Oi, tudo bem? A pronta entrega hoje está aberta até as 18h e ainda temos maçã do amor tradicional e de chocolate. " +
            "A tradicional sai por R$ 10,00 e a de chocolate por R$ 12,00. Para encomendas, o pedido precisa ser feito com 2 dias de antecedência. " +
            "Você pode escolher tudo pelo site: https://loja.example.com/loja. Qualquer coisa me chama aqui."
        val out = Humanizer.finish(emptyList(), longa, 1)
        assertTrue("esperava fracionar: $out", out.size in 2..Humanizer.MAX_PARTS)
        assertTrue(out.all { it.length <= 260 })
        assertTrue("link inteiro", out.any { it.contains("https://loja.example.com/loja") })
    }

    @Test fun listaFicaNumaMensagemSo() {
        val texto = "Hoje temos:\n- Maçã tradicional R$ 10,00\n- Maçã de chocolate R$ 12,00\n- Maçã de Ninho R$ 13,00\n- Maçã de Oreo R$ 13,00\n\n" +
            "Quer que eu te mande o link do site para escolher e finalizar por lá? Assim você já vê as fotos de cada uma e escolhe o horário."
        val out = Humanizer.finish(emptyList(), texto, 1)
        assertTrue(out.first().contains("Maçã tradicional") && out.first().contains("Maçã de Oreo"))
    }

    @Test fun limitaEmojisNaRespostaInteira() {
        val out = Humanizer.finish(listOf("Oi! 😊🍎", "Temos sim ✅ 👍🏽", "Te espero ❤️"), "", 1)
        assertEquals("Oi! 😊", out[0])
        assertEquals("Temos sim", out[1])
        assertEquals("Te espero", out[2])
    }

    @Test fun semEmojis() {
        assertEquals(listOf("Família e bandeira somem"),
            Humanizer.finish(listOf("Família 👨‍👩‍👧 e bandeira 🇧🇷 somem"), "", 0))
    }

    @Test fun tiraDespedidaDeRobo() {
        assertEquals(listOf("Temos sim, R$ 12,00."), Humanizer.finish(listOf("Temos sim, R$ 12,00. Fico à disposição!"), "", 1))
        assertEquals(listOf("Pronto, anotado."), Humanizer.finish(listOf("Pronto, anotado.", "Posso ajudar em algo mais?"), "", 1))
        // Se a única mensagem for a despedida, mantém (não fica sem resposta).
        assertEquals(1, Humanizer.finish(listOf("Espero ter ajudado!"), "", 1).size)
    }

    @Test fun tiraMarkdown() {
        assertEquals(listOf("Preço: *R$ 10*"), Humanizer.finish(listOf("## Preço: **R$ 10**"), "", 1))
    }

    @Test fun excessoDeMensagensEJuntado() {
        val out = Humanizer.finish(listOf("a1", "b2", "c3", "d4", "e5", "f6"), "", 1)
        assertEquals(Humanizer.MAX_PARTS, out.size)
        assertEquals("d4\ne5\nf6", out.last())
    }
}
