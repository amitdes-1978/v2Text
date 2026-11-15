package com.v2text.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the desktop tests/test_textproc.py, so both platforms behave identically. */
class TextProcessorTest {
    private fun clean(s: String, o: CleanOptions = CleanOptions()) = TextProcessor.clean(s, o).text

    @Test fun fillersRemovedAndCapitalised() = assertEquals("So I think we should go", clean("um so I think, uh, we should go"))

    @Test fun fillerInsideWordsUntouched() = assertEquals("The drummer hummed", clean("the drummer hummed"))

    @Test fun scratchThat() = assertEquals("Meet at 6.", clean("Meet at 5. Scratch that. Meet at 6."))

    @Test fun selfCorrectionSameKindOnly() {
        assertEquals("Let's meet at 3 pm", clean("let's meet at 4 pm, actually 3 pm"))
        assertEquals("Send 25 kg", clean("send 20 kg, no wait 25 kg"))
        assertTrue(clean("at 4 pm, actually 3 boxes").contains("4 pm"))
    }

    @Test fun plainNoIsNotACorrection() = assertEquals("I need 5, no 6 is too many", clean("I need 5, no 6 is too many"))

    @Test fun spokenNewlines() = assertEquals("Hello\nWorld\n\nBye", clean("hello new line world new paragraph bye"))

    @Test fun numberedList() = assertEquals(
        "Agenda for today:\n1. Budget review\n2. Hiring plan\n3. Travel",
        clean("agenda for today first budget review, second hiring plan and third travel"))

    @Test fun listNeedsOrderedOrdinals() = assertEquals(
        "The second option is better than the first", clean("the second option is better than the first"))

    @Test fun aliasesExactOnlyNeverShortOrNumeric() {
        val o = CleanOptions(aliases = mapOf("l n g" to "LNG", "command center" to "Command Centre", "5" to "five", "ab" to "X"))
        assertEquals("Check the LNG tank and Command Centre", clean("check the l n g tank and command center", o))
        assertEquals("Ab 5", clean("ab 5", o))
    }

    @Test fun termCase() = assertEquals("UGEL report", clean("ugel report", CleanOptions(terms = listOf("UGEL"))))

    @Test fun snippetsExpandVerbatim() = assertEquals(
        "Here is https://cal.example/me",
        clean("here is my calendar link.", CleanOptions(snippets = mapOf("my calendar link" to "https://cal.example/me"))))

    @Test fun protectedTokensSurvive() {
        val raw = "um vehicle GJ 01 AB 1234 left at 10:30 with 2,500 litres on 12/11/2026 cost ₹45,000"
        val out = clean(raw)
        listOf("GJ 01 AB 1234", "10:30", "2,500 litres", "12/11/2026", "₹45,000").forEach {
            assertTrue("$it missing in: $out", out.contains(it))
        }
        assertTrue(TextProcessor.findProtected(raw).contains("GJ 01 AB 1234"))
    }

    @Test fun changeLog() {
        val r = TextProcessor.clean("um hello")
        assertTrue("fillers" in r.changes)
        assertEquals("Hello", r.text)
    }

    @Test fun devanagariAndGujaratiUntouched() {
        val hi = "मैं कल ऑफिस आऊंगा"
        assertEquals(hi, clean(hi, CleanOptions(fillers = Languages.get("hi").fillers)))
        val gu = "આવતીકાલે ટેન્કર GJ 01 AB 1234 આવશે"
        assertEquals(gu, clean(gu, CleanOptions(fillers = Languages.get("gu").fillers)))
    }

    @Test fun hindiFillerRemoved() =
        assertEquals("मैं कल आऊंगा", clean("अं मैं कल आऊंगा", CleanOptions(fillers = Languages.get("hi").fillers)))
}
