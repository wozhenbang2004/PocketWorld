"""Synthetic explicit CLI home only. Not real account proof."""
import json
import os
from pathlib import Path
import secrets
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import group_input_audience as audience

class GroupAudienceGenerationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='c3-explicit-codex-api-source-');self.home=Path(self.tmp.name)
        self.env={'kind':'local','codexHome':str(self.home),'codexProviderVariables':[]};self.manager=SimpleNamespace(auth_generation_salt=secrets.token_bytes(32))
        (self.home/'config.toml').write_text('cli_auth_credentials_store = "file"\nmodel_provider = "openai"\n')
        (self.home/'auth.json').write_text(json.dumps({'OPENAI_API_KEY':'synthetic-never-a-real-secret-key'}))
    def tearDown(self):self.tmp.cleanup()
    def test_only_selected_exact_sources_produce_one_process_opaque_generation(self):
        original=audience.generation(self.manager,self.env);self.assertEqual(64,len(original));self.assertEqual(original,audience.generation(self.manager,self.env))
        with patch.dict(os.environ,{'UNSELECTED_FIXTURE_SECRET':'not-read-by-this-leaf'}):self.assertEqual(original,audience.generation(self.manager,self.env))
        with patch.dict(os.environ,{'OPENAI_API_KEY':'selected-synthetic-key','CODEX_ACCESS_TOKEN':'unselected-synthetic-token'}):
            selected={**self.env,'codexProviderVariables':['OPENAI_API_KEY']};self.assertNotEqual(original,audience.generation(self.manager,selected));active=audience.child_environment(selected,os.environ)
            self.assertEqual('selected-synthetic-key',active['OPENAI_API_KEY']);self.assertEqual(str(self.home),active['CODEX_HOME']);self.assertNotIn('CODEX_ACCESS_TOKEN',active)
        self.assertNotEqual(original,audience.generation(SimpleNamespace(auth_generation_salt=secrets.token_bytes(32)),self.env))
    def test_rotation_config_drift_unknown_store_link_and_unselected_home_fail_closed(self):
        original=audience.generation(self.manager,self.env);(self.home/'auth.json').write_text(json.dumps({'OPENAI_API_KEY':'different-synthetic-key'}));self.assertNotEqual(original,audience.generation(self.manager,self.env))
        for store in ('keyring','auto','ephemeral'):
            (self.home/'config.toml').write_text('cli_auth_credentials_store = "'+store+'"\n')
            with self.assertRaises(audience.Unavailable):audience.generation(self.manager,self.env)
        (self.home/'config.toml').write_text('cli_auth_credentials_store = "file"\n');(self.home/'auth.json').unlink();(self.home/'auth.json').symlink_to(self.home/'config.toml')
        with self.assertRaises(audience.Unavailable):audience.generation(self.manager,self.env)
        self.assertFalse(audience.configured({'kind':'local'}));self.assertFalse(audience.configured({**self.env,'codexProviderVariables':[{}]}))
    def test_account_configured_or_non_api_mode_never_becomes_api_proof(self):
        config={'config':{'cli_auth_credentials_store':'file','model_provider':'openai'}}
        for account in (None,{}, {'requiresOpenaiAuth':False,'account':None}, {'requiresOpenaiAuth':True,'account':{'type':'chatgpt','email':'not-exported'}}, {'requiresOpenaiAuth':False,'account':{'type':'amazonBedrock'}}):
            with self.assertRaises(audience.Unavailable):audience.actual_api_mode(account,config)
        result=audience.actual_api_mode({'requiresOpenaiAuth':True,'account':{'type':'apiKey'}},config)
        self.assertEqual('apiKey',result['authMode']);self.assertFalse(result['billingBackendVerified']);self.assertNotIn('synthetic',json.dumps(result));self.assertNotIn('email',result)

if __name__=='__main__':unittest.main()
