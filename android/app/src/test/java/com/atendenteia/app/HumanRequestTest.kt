package com.atendenteia.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanRequestTest {

    @Test fun pedidoClaroDeGente() {
        for (t in listOf(
            "atendente", "Atendente, por favor", "humano", "quero falar com um atendente",
            "Quero falar com uma pessoa", "me passa pra um atendente", "chama o atendente",
            "cadê o atendente?", "pessoa real por favor", "tem algum humano aí?",
            "quero falar com atendente humano", "transfere para uma pessoa", "atendente de verdade",
        )) assertTrue(t, Brain.humanRequested(t))
    }

    @Test fun produtoOuCodigoNaoEhPedidoDeGente() {
        for (t in listOf(
            "quero o atendente ia", "Quero o Atendente IA", "#atendente", "oi #atendente",
            "quanto custa o atendente virtual?", "vi o vídeo do atendente de IA", "quero comprar o atendente",
            "quero o atendente", "preciso de um atendente pra minha loja", "como funciona o atendente?",
            "oi, tudo bem?", "atendimento", "ok obrigado",
        )) assertFalse(t, Brain.humanRequested(t))
    }
}
