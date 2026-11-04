"""Thin Activity/Controller source wiring only; actual helper/registry/repository tests are separate."""
from pathlib import Path
import os
import unittest

ROOT = Path(os.environ.get('POCKETWORLD_SOURCE_ROOT', Path(__file__).resolve().parents[2]))


class AttachmentImportContextWiringTest(unittest.TestCase):
    def test_legacy_static_slot_does_not_consume_the_new_session(self):
        source = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/ComposeActivity.kt').read_text()
        self.assertIn('private val legacyAttachmentPicker =', source)
        callback = source.split('private val legacyAttachmentPicker =', 1)[1].split('private val attachmentPicker =', 1)[0]
        self.assertIn('val original = legacyImportTarget; legacyImportTarget = null', callback)
        self.assertIn('controller.importAttachments(Intent(result.data!!), original)', callback)
        self.assertIn('attachmentImportSelectionEnded', callback)
        self.assertNotIn('attachmentPicker.', callback)
        self.assertNotIn('state.taskId', callback)
        self.assertNotIn('state.projectId', callback)
        self.assertNotIn('private var importTaskId', source)
        self.assertNotIn('private var importProjectId', source)

    def test_activity_restores_and_resumes_registrations_without_relaunching(self):
        source = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/ComposeActivity.kt').read_text()
        self.assertIn('if (!attachmentPicker.restore(savedInstanceState)) controller.attachmentImportContextUnavailable()', source)
        self.assertIn('attachmentPicker.resume()', source)
        self.assertIn('attachmentPicker.save(outState)', source)
        self.assertIn('attachmentPicker.close()', source)
        launch = source.split('internal fun pickAttachments(', 1)[1].split('internal fun offerExport(', 1)[0]
        self.assertIn('controller.state.attachmentImporting', launch)
        self.assertIn('attachmentPicker.launch(taskId, projectId)', launch)
        self.assertNotIn('state.projectId', launch)

    def test_controller_uses_the_frozen_target_and_keeps_saved_versus_linked_receipts(self):
        source = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/PocketWorldController.kt').read_text()
        self.assertIn('fun importAttachments(intent: Intent, original:', source)
        receive = source.split('fun importAttachments(intent: Intent, original:', 1)[1].split('fun exportProject()', 1)[0]
        self.assertIn('val received = Intent(intent)', receive)
        self.assertIn('OwnerAttachmentImport.receive(module, received, original)', receive)
        self.assertIn('app.selectOutcomeForTask(task, file.getString("id"), file.getString("sha256")', receive)
        self.assertIn('notice = receipt.notice, error = receipt.error', receive)
        self.assertIn('runCatching { app.features.sendState() }', receive)
        self.assertIn('可能已有文件保留在附件库；未自动重试', receive)
        self.assertNotIn('state.taskId', receive)
        self.assertNotIn('state.projectId', receive)
        self.assertNotIn('error = message(error)', receive)

    def test_nonce_callback_consumes_only_its_original_pin_before_import(self):
        source = (ROOT / 'app/src/main/java/dev/pocketengine/ui/AttachmentImportPicker.java').read_text()
        self.assertIn('"owner_attachment_import_"+original.requestId', source)
        self.assertIn('if(pending!=original)return;', source)
        self.assertLess(source.index('pending=null;ActivityResultLauncher'), source.index('receiver.receive(new Intent(result.getData()),original)'))
        restore = source.split('public boolean restore(', 1)[1]
        self.assertNotIn('.launch(', restore)
        self.assertNotIn('receiver.receive', restore)
        self.assertNotIn('putParcelable', source)
        self.assertNotIn('putString("uri"', source)

    def test_request_keeps_existing_saf_permission_flags_only(self):
        source = (ROOT / 'app/src/main/java/dev/pocketengine/ui/AttachmentImportPicker.java').read_text()
        self.assertIn('Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION', source)
        self.assertNotIn('FLAG_GRANT_WRITE_URI_PERMISSION', source)
        self.assertNotIn('takePersistableUriPermission', source)
        self.assertNotIn('openInputStream', source)


if __name__ == '__main__':
    unittest.main()
