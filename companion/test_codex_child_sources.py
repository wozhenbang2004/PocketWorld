"""Pinned public schema fixtures, real pipes; no model/account inference or child control."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from codex_child_sources import CodexChildSources, PROTOCOL, VERSION, MAX_CHILDREN
from codex_driver import CodexAppServerDriver
from remote_interactions import Progress
from test_codex_driver import FIXTURE


def started(child="child-a", parent="root", version=VERSION):
    return {"thread": {"id": child, "cliVersion": version, "parentThreadId": parent,
                       "source": {"subAgent": {"thread_spawn": {"parent_thread_id": parent, "depth": 1}}}}}


class CodexChildSourcesTests(unittest.TestCase):
    def test_only_exact_native_direct_parent_and_pinned_runtime_can_register(self):
        sources=CodexChildSources();self.assertIsNone(sources.register(started(),"root","turn",None));self.assertIsNone(sources.register(started(),"root","turn","0.165.0"));self.assertIsNotNone(sources.register(started(),"root","turn",VERSION))
        for mutation in [lambda x:x["thread"].update(parentThreadId="foreign"),lambda x:x["thread"]["source"]["subAgent"]["thread_spawn"].update(parent_thread_id="foreign"),lambda x:x["thread"]["source"]["subAgent"]["thread_spawn"].update(depth=True),lambda x:x["thread"].update(cliVersion="0.165.0")]:
            x=started("other");mutation(x);self.assertIsNone(sources.register(x,"root","turn",VERSION))

    def test_assistant_json_names_and_nested_or_missing_lineage_are_not_proof(self):
        sources=CodexChildSources()
        for value in [{"text":json.dumps(started())}, {"thread":{"id":"child","cliVersion":VERSION,"parentThreadId":"root","source":"cli"}},started("root"),started("child",parent="another")]:self.assertIsNone(sources.register(value,"root","turn",VERSION))
        nested=started();nested['thread']['source']['subAgent']['thread_spawn']['depth']=2;self.assertIsNone(sources.register(nested,"root","turn",VERSION));self.assertEqual({},sources.children)

    def test_two_children_same_tool_or_text_identity_do_not_collide_or_merge(self):
        sources=CodexChildSources();progress=Progress("codex_app_server")
        for child in ["child-a","child-b"]:
            sources.register(started(child),"root","turn",VERSION)
            row=sources.event("item/agentMessage/delta",{"threadId":child,"turnId":"child-turn","itemId":"same","delta":"same text"},"root","turn",VERSION)
            progress.add(row.pop('kind'),row.pop('nativeType'),row.pop('itemId'),**row)
        rows=progress.snapshot()['events'];self.assertEqual(2,len(rows));self.assertEqual({'child-a','child-b'},{r['nativeChildThreadId'] for r in rows})

    def test_identical_received_chunks_are_not_falsely_deduplicated_as_provider_replay(self):
        sources=CodexChildSources();sources.register(started(),"root","turn",VERSION);progress=Progress("codex_app_server")
        for _ in range(2):
            row=sources.event('item/agentMessage/delta',{'threadId':'child-a','turnId':'child-turn','itemId':'same','delta':'ha'},'root','turn',VERSION);progress.add(row.pop('kind'),row.pop('nativeType'),row.pop('itemId'),**row)
        self.assertEqual(['ha','ha'],[r['text'] for r in progress.snapshot()['events']]);self.assertNotEqual(*[r['driverObservationIndex'] for r in progress.snapshot()['events']])

    def test_reasoning_usage_questions_and_unknown_source_do_not_become_public_child_text(self):
        sources=CodexChildSources();sources.register(started(),"root","turn",VERSION)
        for method in ['item/reasoning/textDelta','item/reasoning/summaryTextDelta','thread/tokenUsage/updated','item/tool/requestUserInput','unknown']:
            self.assertIsNone(sources.event(method,{'threadId':'child-a','turnId':'child-turn','itemId':'reasoning','delta':'HIDDEN_PRIVATE'},'root','turn',VERSION))
        self.assertIsNone(sources.event('item/agentMessage/delta',{'threadId':'foreign','turnId':'child-turn','itemId':'x','delta':'foreign'},'root','turn',VERSION))
        self.assertIsNone(sources.event('item/completed',{'threadId':'child-a','turnId':'child-turn','item':{'id':'r','type':'reasoning','summary':'HIDDEN_PRIVATE'}},'root','turn',VERSION))

    def test_malformed_public_child_fields_are_ignored_without_crashing_original_parent(self):
        sources=CodexChildSources();sources.register(started(),"root","turn",VERSION)
        for method,params in [("item/agentMessage/delta",{'threadId':{},'turnId':'ct','itemId':'x','delta':'not attributed'}),("item/agentMessage/delta",{'threadId':'child-a','turnId':'ct','itemId':'x','delta':'\ud800'}),("item/started",{'threadId':'child-a','turnId':'ct','item':{'id':'x','type':[]}}),("turn/completed",{'threadId':'child-a','turn':{'id':'ct','status':{}}})]:
            self.assertIsNone(sources.event(method,params,'root','turn',VERSION))
        self.assertIsNotNone(sources.event('item/agentMessage/delta',{'threadId':'child-a','turnId':'ct','itemId':'x','delta':'valid public text'},'root','turn',VERSION))

    def test_conflicting_source_is_quarantined_not_reparented_and_registry_is_bounded(self):
        sources=CodexChildSources();sources.register(started(),"root","turn",VERSION);self.assertIsNone(sources.register(started(parent='foreign'),'root','turn',VERSION));self.assertNotIn('child-a',sources.children);self.assertIsNone(sources.register(started(),'root','turn',VERSION))
        for i in range(MAX_CHILDREN+2):sources.register(started('child-'+str(i)),'root','turn',VERSION)
        self.assertEqual(MAX_CHILDREN,len(sources.children)+len(sources.conflicted));self.assertIsNone(sources.register(started('extra'),'root','turn',VERSION))

    def test_public_tool_observation_does_not_copy_arguments_paths_or_mutate_input(self):
        sources=CodexChildSources();sources.register(started(),"root","turn",VERSION);params={'threadId':'child-a','turnId':'ct','item':{'id':'tool','type':'commandExecution','command':'PRIVATE_COMMAND','cwd':'PRIVATE_PATH'}};before=copy.deepcopy(params);row=sources.event('item/started',params,'root','turn',VERSION)
        self.assertEqual(before,params);self.assertEqual('tool',row['kind']);self.assertNotIn('PRIVATE_',str(row));self.assertNotIn('approval',row)


PEER=FIXTURE.replace("{'userAgent':'protocol-fixture'}","{'userAgent':'codex_cli_rs/0.165.0' if mode=='unknown-version' else 'codex_cli_rs/0.166.0-alpha.1'}",1)
needle="        if mode=='final-without-start-ack':"
insert=r'''        for child in ['child-a','child-b']:
            parent='foreign' if mode=='foreign-parent' else 'fixture-thread'
            emit({'method':'thread/started','params':{'thread':{'id':child,'cliVersion':'0.166.0-alpha.1','parentThreadId':parent,'source':{'subAgent':{'thread_spawn':{'parent_thread_id':parent,'depth':1}}}}}})
            for _ in range(2):emit({'method':'item/agentMessage/delta','params':{'threadId':child,'turnId':'child-turn','itemId':'same','delta':'public '+child}})
            emit({'method':'item/started','params':{'threadId':child,'turnId':'child-turn','item':{'id':'same-tool','type':'commandExecution','command':'DO_NOT_COPY_CHILD_COMMAND','cwd':'DO_NOT_COPY_CHILD_PATH'}}})
            emit({'method':'item/reasoning/textDelta','params':{'threadId':child,'turnId':'child-turn','itemId':'private','delta':'HIDDEN_CHILD_REASONING'}})
            emit({'method':'thread/tokenUsage/updated','params':{'threadId':child,'turnId':'child-turn','tokenUsage':{'total':{'totalTokens':999999}}}})
            emit({'method':'item/completed','params':{'threadId':child,'turnId':'child-turn','item':{'id':'same','type':'agentMessage','text':'complete public '+child}}})
            emit({'method':'turn/completed','params':{'threadId':child,'turn':{'id':'child-turn','status':'completed'}}})
        if mode=='malformed-child':
            emit({'method':'item/agentMessage/delta','params':{'threadId':{},'turnId':'child-turn','itemId':'x','delta':'unattributed'}})
            emit({'method':'item/started','params':{'threadId':'child-a','turnId':'child-turn','item':{'id':'x','type':[]}}})
            emit({'method':'turn/completed','params':{'threadId':'child-a','turn':{'id':'child-turn','status':{}}}})
        emit({'id':987,'method':'item/commandExecution/requestApproval','params':{'threadId':'child-a','turnId':'child-turn','itemId':'child-command','command':'must be declined','cwd':str(Path.cwd())}})
        continue
'''
assert needle in PEER;PEER=PEER.replace(needle,insert+needle,1)
needle="    elif value.get('id')==42 and 'result' in value:"
insert=r'''    elif value.get('id')==987 and 'result' in value:
        assert value['result']['decision']=='decline'
        from pathlib import Path
        Path('child-declined').write_text('1')
        emit({'method':'item/agentMessage/delta','params':{'threadId':'fixture-thread','turnId':'fixture-turn','itemId':'parent','delta':'PARENT_ONLY_FINAL'}})
        emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'completed'}}})
'''
PEER=PEER.replace(needle,insert+needle,1)


class CodexChildPipeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='codex-public-child-pipes-');self.root=Path(self.temp.name);self.peer=self.root/'peer.py';self.peer.write_text(PEER);self.processes=[]
    def tearDown(self):
        for p in self.processes:
            if p.poll() is None:p.terminate()
            try:p.wait(timeout=2)
            except subprocess.TimeoutExpired:p.kill();p.wait()
            p.stdin.close();p.stdout.close()
        self.temp.cleanup()
    def run_job(self,mode='success'):
        p=subprocess.Popen([sys.executable,str(self.peer),mode],cwd=self.root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE);self.processes.append(p);states=[];driver=CodexAppServerDriver(p,str(self.root),False,states.append,lambda:False,5)
        try:return driver.run('explicit parent prompt'),states,driver
        finally:driver.close_steering();p.stderr.close()
    def test_real_original_pipes_keep_two_children_public_only_and_parent_result_independent(self):
        result,states,driver=self.run_job();self.assertEqual('PARENT_ONLY_FINAL',result['output']);self.assertEqual('completed',result['status']);self.assertEqual('1',(self.root/'child-declined').read_text());self.assertEqual('1',(self.root/'codex-turn-count').read_text());self.assertEqual([],result['pendingApprovals']);self.assertEqual([],result['approvalReceipts'])
        rows=result['progress']['events'];children=[r for r in rows if r.get('childSourceProtocol')==PROTOCOL];self.assertEqual({'child-a','child-b'},{r['nativeChildThreadId'] for r in children});self.assertEqual(4,len([r for r in children if r['nativeType']=='item/agentMessage/delta']));self.assertNotIn('HIDDEN_CHILD_REASONING',str(states));self.assertNotIn('DO_NOT_COPY_CHILD',str(states));self.assertNotIn('999999',str(states));self.assertFalse(any(s.get('pendingApprovals') for s in states));self.assertTrue(any(r['kind']=='child_status' for r in children));self.assertEqual('fixture-turn',driver.completed['id'])
    def test_malformed_child_public_metadata_does_not_abort_or_finalize_original_parent(self):
        result,states,driver=self.run_job('malformed-child');self.assertEqual('PARENT_ONLY_FINAL',result['output']);self.assertEqual('fixture-turn',driver.completed['id']);self.assertEqual('completed',result['status']);self.assertTrue((self.root/'child-declined').exists());self.assertFalse(any(s.get('pendingApprovals') for s in states))
    def test_unknown_runtime_leaves_child_lineage_unavailable_without_changing_parent_execution(self):
        result,states,_=self.run_job('unknown-version');self.assertEqual('PARENT_ONLY_FINAL',result['output']);self.assertFalse(any(r.get('childSourceProtocol') for r in result['progress']['events']));self.assertNotIn('HIDDEN_CHILD_REASONING',str(states));self.assertTrue((self.root/'child-declined').exists())
    def test_foreign_parent_text_and_child_terminal_never_enter_parent_or_become_a_resume_handle(self):
        result,states,_=self.run_job('foreign-parent');self.assertEqual('PARENT_ONLY_FINAL',result['output']);self.assertFalse(any(r.get('childSourceProtocol') for r in result['progress']['events']));self.assertEqual([],result['approvalReceipts']);self.assertTrue((self.root/'child-declined').exists())
