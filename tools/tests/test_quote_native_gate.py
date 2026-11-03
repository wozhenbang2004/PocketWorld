"""Static wiring checks plus a host-side model; device Compose coverage is separate."""
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/kotlin/dev/pocketengine/ui/PocketWorldApp.kt"

class QuoteGateTest(unittest.TestCase):
    def test_production_quote_state_is_shared_with_live_exit_guard(self):
        source = SOURCE.read_text(encoding="utf-8")
        root = source.split("internal fun PocketWorldApp", 1)[1].split("private fun ChatScreen", 1)[0]
        chat = source.split("private fun ChatScreen", 1)[1]
        guard = root.split("fun canLeaveNative(): Boolean", 1)[1].split("if (controller.notificationPreviewsOpen)", 1)[0]
        self.assertIn("val chatQuoteMessage = remember { mutableStateOf<Triple<Long, String, UiMessage>?>(null) }", root)
        self.assertIn("chatQuoteGeneration.longValue += 1", root)
        self.assertIn("chatQuoteGeneration.longValue < Long.MAX_VALUE", root)
        self.assertIn("chatQuoteMessage.value = Triple(chatQuoteGeneration.longValue, conversation, message)", root)
        self.assertIn("controller.state.conversationId == quoteConversation && chatQuoteMessage.value?.second != quoteConversation", root)
        self.assertIn("chatQuoteMessage.value != null", guard)
        self.assertIn("requestWebWorkspace(::canLeaveNative)", root)
        self.assertIn("quoteMessageState: MutableState<Triple<Long, String, UiMessage>?>", chat)
        self.assertIn("var quoteMessage by quoteMessageState", chat)
        self.assertIn("openQuote(message)", chat)
        self.assertIn("if (quoteMessage?.first == token) quoteMessage = null", chat)
        self.assertIn("if (conversation == controller.state.conversationId) key(token) {", chat)
        self.assertNotIn("var quoteMessage by remember(state.conversationId)", chat)

    def test_late_open_blocks_recheck_and_dismiss_restores_only_its_own_sheet(self):
        # Models a stable mutable owner captured by a pending switch; does not emulate Compose.
        cell = {"quote": None}
        can_leave = lambda: cell["quote"] is None
        confirmation = can_leave
        self.assertTrue(confirmation())
        message = object(); old = (1, message); cell["quote"] = old
        self.assertFalse(confirmation())
        cell["quote"] = None
        self.assertTrue(confirmation())
        new = (2, message); cell["quote"] = new
        if cell["quote"][0] == old[0]: cell["quote"] = None
        self.assertFalse(confirmation())
        self.assertIs(cell["quote"], new)

    def test_stale_conversation_effect_cannot_clear_new_quote(self):
        state = {"current": "B", "quote": (2, "B", object())}
        def effect(expected):
            if state["current"] == expected and state["quote"] is not None and state["quote"][1] != expected:
                state["quote"] = None
        effect("A")
        self.assertEqual("B", state["quote"][1])
        effect("B")
        self.assertEqual("B", state["quote"][1])
        state["current"] = "C"; effect("C")
        self.assertIsNone(state["quote"])

    def test_same_message_reopen_uses_distinct_composition_identity(self):
        # Model the Compose key boundary; actual mounted composition is separately unverified.
        old = {"token": 1, "source": "same", "pending": "old-request"}
        new = {"token": 2, "source": "same", "pending": None}
        self.assertNotEqual(old["token"], new["token"])
        old_result = {"requestId": "old-request", "ok": True}
        self.assertNotEqual(new["pending"], old_result["requestId"])
        self.assertEqual("same", old["source"])
        self.assertEqual(old["source"], new["source"])

if __name__ == "__main__": unittest.main()
