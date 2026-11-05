"""Unified quote entry wiring; native transaction and Web callback tests run separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class QuoteGateTest(unittest.TestCase):
    def test_production_quote_is_in_the_only_frontend_and_its_back_stack(self):
        app = (ROOT / 'web-ui/src/App.tsx').read_text()
        host = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebBridge.kt').read_text()
        self.assertIn('if(messageQuoteTarget){setMessageQuoteTarget(null);return true}', app)
        self.assertIn('messageQuoteTarget&&<MessageQuote', app)
        self.assertIn('messageQuoteTarget.key}:${messageQuoteTarget.epoch}:${messageQuoteTarget.messageId}', app)
        self.assertIn('PocketWebMessageQuotes.COMMANDS', host)
        self.assertIn('messageQuotes.clear()', host)
        self.assertNotIn('app.openNativeWorkspace', host)

    def test_late_open_is_retired_and_cannot_replace_a_new_quote(self):
        quote = (ROOT / 'web-ui/src/MessageQuote.tsx').read_text()
        self.assertIn('const request = ++generation.current', quote)
        self.assertIn('!alive.current || generation.current !== request', quote)
        self.assertIn("bridge.command('messageQuote.close', { windowId: value.windowId })", quote)
        self.assertIn('alive.current = false; generation.current++', quote)

    def test_native_staging_keeps_exact_original_message_and_draft_revision(self):
        source = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebMessageQuotes.kt').read_text()
        for expected in ['draft.conversationId == controller.state.conversationId.orEmpty()',
                         'locator.optString("conversationId") == draft.conversationId',
                         'controller.webWindowTicket() == original.owner',
                         'draft.revision == original.draftRevision',
                         'message.sourceRevision == original.sourceRevision',
                         'message.ownerOriginalLocator?.toString() == original.locator',
                         'controller.stageOwnerOriginalReferenceReceipt(']:
            self.assertIn(expected, source)

    def test_reopening_has_new_native_identity_and_close_targets_only_that_window(self):
        source = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebMessageQuotes.kt').read_text()
        self.assertIn('Window(UUID.randomUUID().toString()', source)
        self.assertIn('if (window?.id == text(args, "windowId")) clear()', source)
        self.assertIn('window !== original', source)
        self.assertIn('check(!original.submitted)', source)
        self.assertIn('original.submitted = true', source)

if __name__ == '__main__':
    unittest.main()
