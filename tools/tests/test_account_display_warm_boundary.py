"""Focused source contracts for account initialization versus owner state reads."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/dev/pocketengine/EngineApplication.java"


class AccountDisplayWarmBoundaryTest(unittest.TestCase):
    def test_state_only_projects_already_loaded_account_metadata(self):
        source = APP.read_text()
        start = source.index("    public JSONObject state()throws Exception {")
        end = source.index("    @Override public void onTerminate()", start)
        state = source[start:end]
        self.assertNotIn("warmSelectedAccountMetadata", state)
        self.assertNotIn("accountSnapshot", state)
        self.assertNotIn("accounts()", state)
        self.assertIn('selected.publicJson().put("accountModelAvailable",', state)
        self.assertIn("ModelGateway.accountModelAvailableForDisplay(selected.accountModel)", state)

    def test_existing_capability_worker_publishes_app_state_after_warm(self):
        source = APP.read_text()
        warm = source.index("nativeOwnerWorkflows.warmSelectedAccountMetadata()")
        start = source.rindex("capabilityWorkers.execute(()->{", 0, warm)
        end = source.index("remoteOwnerWorkflows=new", warm)
        worker = source[start:end]
        self.assertIn("if(nativeOwnerWorkflows.warmSelectedAccountMetadata())main.post", worker)
        self.assertIn("sendState();features.sendState();", worker)
        self.assertLess(worker.index("warmSelectedAccountMetadata()"), worker.index("sendState();features.sendState();"))
        self.assertEqual(1, worker.count("warmSelectedAccountMetadata()"))


if __name__ == "__main__":
    unittest.main()
