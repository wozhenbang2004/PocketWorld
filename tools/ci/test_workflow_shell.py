"""Execute the actual candidate workflow publish shell using a fake Python command."""
import os,subprocess,tempfile,unittest
from pathlib import Path
import yaml
class Tests(unittest.TestCase):
 def setUp(self):
  self.doc=yaml.safe_load(Path(__file__).with_name('github-release-workflow.candidate.yml').read_text())
  self.script=self.doc['jobs']['publish']['steps'][-1]['run']
 def invoke(self,resume='',activate='false',recover='false'):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as t:
   root=Path(t).resolve();capture=root/'captured';fake=root/'python3'
   fake.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$CAPTURE"\n');fake.chmod(0o700)
   env={**os.environ,'PATH':str(root)+':/usr/bin:/bin','CAPTURE':str(capture),'RUNNER_TEMP':str(root),'RELEASE_ACTIVATE':activate,'RESUME_RELEASE_ID':resume,'RECOVER_ORPHAN_TAG':recover}
   result=subprocess.run(['/bin/bash','-c',self.script],env=env,capture_output=True,text=True)
   return result.returncode,capture.read_text().splitlines() if capture.exists() else []
 def test_default_has_no_activation_flag(self):
  code,args=self.invoke();self.assertEqual(code,0);self.assertEqual(args[:3],['-B','tools/ci/workflow-release.py','publish']);self.assertNotIn('--activate',args)
 def test_explicit_auto_publish_no_resume_parameters(self):
  code,args=self.invoke('123','true','true');self.assertEqual(code,0);self.assertIn('--activate',args);self.assertNotIn('--resume-release-id',args);self.assertNotIn('--recover-orphan-tag',args)
 def test_resume_environment_is_not_forwarded(self):
  code,args=self.invoke('1; echo injected','true');self.assertEqual(code,0);self.assertNotIn('--resume-release-id',args)
 def test_unconditional_acceptance_gate_remains(self):
  script=self.doc['jobs']['publish']['steps'][0]['run']
  result=subprocess.run(['/bin/bash','-c',script],capture_output=True,text=True)
  self.assertNotEqual(result.returncode,0)
 def test_same_run_artifact_id_and_least_privilege(self):
  jobs=self.doc['jobs'];self.assertEqual(jobs['build']['permissions']['contents'],'read');self.assertEqual(jobs['publish']['permissions']['contents'],'write')
  download=next(s for s in jobs['publish']['steps'] if s.get('uses','').startswith('actions/download-artifact'))
  self.assertEqual(download['with']['artifact-ids'],'${{ needs.build.outputs.release_artifact_id }}')
 def test_workflow_auto_activation_default(self):
  trigger=self.doc.get('on',self.doc.get(True))
  self.assertIs(trigger['workflow_dispatch']['inputs']['activate']['default'],True)
  env=self.doc['jobs']['publish']['steps'][-1]['env']
  self.assertIn("github.event_name == 'push' || inputs.activate",env['RELEASE_ACTIVATE'])
  self.assertNotIn('resume_release_id',trigger['workflow_dispatch']['inputs'])
if __name__=='__main__':unittest.main()
