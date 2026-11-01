"""Real production Companion HTTP fixture; launcher validates bytes but NEVER executes Skill code.

Readiness in this test is synthetic metadata. It proves wire integration, not bubblewrap isolation.
"""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import threading
from urllib.parse import urlsplit

repo = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(repo / "companion"))
from pocket_companion import CompanionHandler, CompanionServer, JobManager

TOKEN = "skill-http-fixture-token-" + "x" * 32
temporary = tempfile.TemporaryDirectory(prefix="pwskill-companion-fixture-")
root = Path(temporary.name)
spool = root / "spool"
spool.mkdir()
helper = repo / "app/src/main/java/dev/pocketengine/features/skills/companion/pocketworld_skill_executor.py"
helper_sha = hashlib.sha256(helper.read_bytes()).hexdigest()
policy = {"installationId": "fixture-install", "grantIdentity": "fixture-grant", "ownerId": "owner", "spaceId": "private",
          "workspaceId": "workspace-1", "runtime": "python3", "runtimeImageDigest": "a" * 64,
          "launcher": ["/usr/bin/python3", "-I", "-B"], "readPaths": ["input/data.txt"], "writePaths": ["output/result.bin"], "outputPaths": ["output/result.bin"], "workingDirectory": ".",
          "runtimeSha256": "b" * 64, "runtimeVersion": "fixture-3.12",
          "processPolicy": "single_process",
          "maxSeconds": 120, "maxOutputBytes": 65536, "grantExpiresAt": 9999999999999, "helperSha256": helper_sha}
policy_raw = json.dumps(policy, sort_keys=True).encode()
policy_sha = hashlib.sha256(policy_raw).hexdigest()
(spool / ".pwskill-policy.json").write_bytes(policy_raw)
ready = {"format": "pocketworld.skill-canonical-bwrap.v1", "available": True, "installationId": "fixture-install", "helperSha256": helper_sha,
         "policySha256": policy_sha, "runtimeImageDigest": "a" * 64, "runtimeSha256": "b" * 64, "runtimeVersion": "fixture-3.12", "workspaceRevision": "revision-1", "expiresAt": 9999999999999}
ready_raw = json.dumps(ready, sort_keys=True).encode()
(spool / ".pwskill-ready.json").write_bytes(ready_raw)
launcher = spool / "fixture-launcher"
launcher.write_text("""#!/usr/bin/python3
import base64, hashlib, importlib.util, json, pathlib, sys, time
sys.dont_write_bytecode = True
source = pathlib.Path(%r)
spec = importlib.util.spec_from_file_location('production_skill_launcher', source)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
raw = pathlib.Path(sys.argv[2]).read_bytes()
assert hashlib.sha256(raw).hexdigest() == sys.argv[3]
config = json.loads(pathlib.Path('.pwskill-policy.json').read_text())
config['policySha256'] = hashlib.sha256(pathlib.Path('.pwskill-policy.json').read_bytes()).hexdigest()
p, key, files = module.validate_bundle(config, raw)
grant = json.loads(raw)['grant']
with open('.fixture-dispatches', 'a') as marker: marker.write(grant['operationId'] + '\\n')
pathlib.Path('.fixture-seen-bundle').write_bytes(raw)
if '--block' in p['argv']:
    while True: time.sleep(.02)
output = b'\\x00actual fixture output\\xff'
budget = p['request']['maxOutputBytes']; truncated = len(output) > budget; output = output[:budget]
result = {'format':'pocketworld.skill-receipt.v1','operationKey':key,'operationId':grant['operationId'],'planDigest':p['planDigest'],
          'installationId':config['installationId'],'environmentBinding':grant['environmentBinding'],'state':'COMPLETED','exitCode':0,'outputBase64':base64.b64encode(output).decode(),
          'outputSha256':hashlib.sha256(output).hexdigest(),'truncated':truncated,'workspaceMode':'isolated_copy','canonicalWorkspaceModified':False}
workspace = pathlib.Path('.fixture-execution-copy'); target = workspace / p['request']['outputPaths'][0]; target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(b'\\x89PNG\\x00\\xff')
config['spoolRoot'] = pathlib.Path.cwd()
artifact_path, artifact_sha, artifact_truncated = module.export_artifacts(config, p, key, workspace, budget - len(output), grant)
result.update(artifactManifestPath=artifact_path, artifactManifestSha256=artifact_sha, artifactsTruncated=artifact_truncated)
print(json.dumps(result, separators=(',', ':')), flush=True)
""" % str(helper))
launcher.chmod(0o700)
manager = JobManager({"stateDir": str(root / "state"), "environments": [
    {"id": "computer", "kind": "local", "root": str(spool), "workers": ["command"], "writeAllowed": True,
     "commands": {"skill_launcher": str(launcher)}}]}, TOKEN)
mode = {"lose_ack": False, "late_post": False, "verified_false": False, "tamper_read": False}
entered = threading.Event()
release = threading.Event()


class Handler(CompanionHandler):
    def _route(self, post):
        path = urlsplit(self.path).path
        if path.startswith("/__fixture/"):
            action = path.rsplit("/", 1)[-1]
            if action in mode:
                mode[action] = True
            if action == "release":
                release.set()
            if action == "delete_ready":
                (spool / ".pwskill-ready.json").unlink()
            if action == "marker":
                value = (spool / ".fixture-dispatches").read_text().splitlines() if (spool / ".fixture-dispatches").exists() else []
                result = {"count": len(value), "entered": entered.is_set(), "operations": value}
            elif action == "bundle":
                result = json.loads((spool / ".fixture-seen-bundle").read_bytes())
            else:
                result = {"ok": True}
            self._send(200, {"ok": True, "result": result})
            return
        if post and path == "/v1/jobs" and mode["late_post"]:
            entered.set()
            if not release.wait(8):
                self._send(503, {"ok": False, "message": "Fixture late POST timed out"})
                return
        super()._route(post)

    def _send(self, status, value):
        if self.path == "/v1/jobs" and mode["lose_ack"]:
            self.close_connection = True
            self.connection.close()
            return
        if mode["verified_false"] and self.path == "/v1/workspaces/computer/file" and isinstance(value.get("result"), dict):
            value["result"]["verified"] = False
        if mode["tamper_read"] and self.path.startswith("/v1/workspaces/computer/file?") and isinstance(value.get("result"), dict):
            result = value["result"]
            if __import__("re").fullmatch(r"\.pwskill-[a-f0-9]{64}\.json", result.get("path", "")):
                bundle = json.loads(result["content"])
                bundle["resources"]["references/input.txt"] = "dGFtcGVyZWQ="
                raw = json.dumps(bundle).encode()
                result.update(content=raw.decode(), bytes=len(raw), version="sha256:" + hashlib.sha256(raw).hexdigest())
        super()._send(status, value)


server = CompanionServer(("127.0.0.1", 0), manager)
server.RequestHandlerClass = Handler
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
print(json.dumps({"base": "http://127.0.0.1:" + str(server.server_port), "token": TOKEN, "policySha": policy_sha,
                  "readySha": hashlib.sha256(ready_raw).hexdigest(), "helperSha": helper_sha}), flush=True)
try:
    sys.stdin.buffer.read()
finally:
    release.set()
    server.shutdown()
    server.server_close()
    manager.close()
    temporary.cleanup()
