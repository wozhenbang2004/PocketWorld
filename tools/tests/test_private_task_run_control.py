"""Wiring checks only. The registered native test executes actual SQL/run/control queues."""
import json
import os
from pathlib import Path
import re
import unittest

ROOT = Path(os.environ.get('POCKETWORLD_SOURCE_ROOT', Path(__file__).resolve().parents[2]))


class PrivateTaskRunControlWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/PocketWorldController.kt').read_text()

    def test_task_projection_always_contains_the_original_tuple(self):
        body = self.controller.split('private fun reviewedTaskInput(', 1)[1].split('fun stopReviewedTask(', 1)[0]
        self.assertIn('.put("runId", task.raw.optString("runId"))', body)
        self.assertIn('.put("epoch", task.raw.optInt("coordinatorEpoch"))', body)
        self.assertNotIn('if (', body)

    def test_top_level_stop_and_id_stop_use_only_an_existing_original_projection(self):
        phone = self.controller.split('fun stopPhone()', 1)[1].split('internal fun releaseHeldPhoneResources', 1)[0]
        self.assertIn('state.tasks.find { it.id == state.activeTaskId }', phone)
        self.assertIn('stopReviewedTask', phone)
        self.assertNotIn('native("stopRun"', phone)
        task = self.controller.split('fun stopTask(taskId: String)', 1)[1].split('private fun reviewedTaskInput', 1)[0]
        self.assertIn('state.tasks.find { it.id == taskId }', task)
        self.assertIn('stopReviewedTask(task)', task)
        self.assertNotIn('JSONObject().put("taskId"', task)

    def test_native_regression_is_registered_and_keeps_the_existing_resume_route(self):
        registry = json.loads((ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual('dev.pocketengine.PrivateTaskRunControlNativeTest', registry['private-task-run-control']['class'])
        self.assertTrue(registry['private-task-run-control']['robolectric'])
        body = self.controller.split('fun resumeTask(task: UiTask, feedback: String)', 1)[1].split('fun resolve(', 1)[0]
        self.assertIn('reviewedTaskInput(task)', body)
        self.assertIn('native("resumeTask", input)', body)
        self.assertIn('input.put("taskRevision", task.raw.getLong("groupStateRevision"))', body)


if __name__ == '__main__':
    unittest.main()
