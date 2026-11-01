import hashlib, json, os, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path

PRODUCTION=Path(__file__).parents[2]/'main/java/dev/pocketengine/features/local/project/ProjectGitProgram.java'
embedded=PRODUCTION.read_text().split('    private static final String SOURCE="""\n',1)[1].rsplit('""";',1)[0].replace('\\\\','\\')
PROGRAM=Path(os.environ.get('TMPDIR',tempfile.gettempdir()))/'c2-production-git-under-test.py'
PROGRAM.write_text(embedded)
print('PRODUCTION_FIXED_GIT_PROGRAM_SHA256='+hashlib.sha256(embedded.encode()).hexdigest(),flush=True)
GIT=Path(shutil.which('git')).resolve()
GIT_SHA=hashlib.sha256(GIT.read_bytes()).hexdigest()

class ActualGitProtocol(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='c2-owned-git-',dir=os.environ.get('TMPDIR'))
        self.root=Path(self.temporary.name)
        self.env={**os.environ,'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':'/dev/null','GIT_CONFIG_SYSTEM':'/dev/null','GIT_AUTHOR_NAME':'PocketWorld Fixture','GIT_COMMITTER_NAME':'PocketWorld Fixture','GIT_AUTHOR_EMAIL':'fixture@example.invalid','GIT_COMMITTER_EMAIL':'fixture@example.invalid'}
    def tearDown(self):
        self.temporary.cleanup()
    def git(self,*args,ok=(0,)):
        p=subprocess.run([str(GIT),*args],cwd=self.root,env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.assertIn(p.returncode,ok,p.stderr.decode(errors='replace'))
        return p.stdout.decode().strip()
    def run_protocol(self,action,ok=True,**rest):
        request=dict(version=1,action=action,gitExecutableSha256=GIT_SHA,**rest)
        p=subprocess.run([sys.executable,str(PROGRAM),json.dumps(request)],cwd=self.root,env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        self.assertEqual(p.stderr,b'')
        result=json.loads(p.stdout)
        self.assertEqual(result['ok'],ok,(result,p.returncode))
        self.assertEqual(p.returncode,0 if ok else 2,(result,p.returncode))
        return result['result'] if ok else result
    def snapshot(self):
        return self.run_protocol('status')
    def file(self,path,content):
        p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    def init(self):
        s=self.snapshot();self.assertFalse(s['repositoryPresent'])
        r=self.run_protocol('init',expectedWorktreeSha256=s['worktreeSha256']);self.assertTrue(r['initialized']);return self.snapshot()
    def stage(self,paths,**extra):
        return self.run_protocol('stage',expected=self.snapshot()['pin'],paths=paths,**extra)
    def commit(self,paths,message='reviewed local fixture',**extra):
        snapshot=self.snapshot(); rows={row['path']:row for row in snapshot['entries']}
        reviewed=[]
        for path in paths:
            entries=rows[path]['indexEntries'];entry=entries[0] if entries else {'mode':'0','objectId':'absent'}
            reviewed.append(dict(path=path,mode=entry['mode'],objectId=entry['objectId']))
        parents=[] if snapshot['pin']['head']=='unborn' else [snapshot['pin']['head']]
        parents.extend(p for p in snapshot['mergeParentIds'] if p not in parents)
        return self.run_protocol('commit',expected=snapshot['pin'],paths=paths,reviewedEntries=reviewed,parents=parents,message=message,authorName='PocketWorld Fixture',authorEmail='fixture@example.invalid',**extra)
    def seed(self):
        self.file('main.txt','one\n');self.file('other.txt','other\n');self.init();self.stage(['main.txt','other.txt']);self.commit(['main.txt','other.txt'])
    def test_no_repo_init_is_explicit_and_does_not_stage(self):
        self.file('main.txt','one\n');self.init();s=self.snapshot();self.assertEqual(s['pin']['head'],'unborn');self.assertEqual(s['entries'][0]['indexStatus'],'?');self.assertFalse((self.root/'.git/hooks/pre-commit').exists());self.assertFalse((self.root/'.git/index').exists())
    def test_staged_then_edited_has_two_real_git_layers_and_unstage_keeps_worktree(self):
        self.seed();self.file('main.txt','two\n');self.stage(['main.txt']);self.file('main.txt','three\n');s=self.snapshot();main=next(r for r in s['entries'] if r['path']=='main.txt');self.assertEqual((main['indexStatus'],main['worktreeStatus']),('M','M'))
        staged=self.run_protocol('diff',expected=s['pin'],path='main.txt',layer='head_index');working=self.run_protocol('diff',expected=s['pin'],path='main.txt',layer='index_worktree');self.assertIn('+two',staged['diff']);self.assertNotIn('+three',staged['diff']);self.assertIn('-two',working['diff']);self.assertIn('+three',working['diff'])
        self.run_protocol('unstage',expected=s['pin'],paths=['main.txt']);s=self.snapshot();main=next(r for r in s['entries'] if r['path']=='main.txt');self.assertEqual((main['indexStatus'],main['worktreeStatus']),('.','M'));self.assertEqual((self.root/'main.txt').read_text(),'three\n')
    def test_commit_contains_only_selected_staged_files_and_preserves_unstaged_content(self):
        self.seed();self.file('main.txt','staged main\n');self.file('other.txt','staged other\n');self.stage(['main.txt','other.txt']);self.file('main.txt','newer worktree\n');before_index=(self.root/'.git/index').read_bytes();result=self.commit(['main.txt']);self.assertTrue(result['headPublished']);self.assertEqual(self.git('show','HEAD:main.txt'),'staged main');self.assertEqual(self.git('show','HEAD:other.txt'),'other');history=self.run_protocol('history',expected=self.snapshot()['pin']);self.assertEqual(history['commits'][0]['commitId'],result['commitId']);self.assertEqual(history['commits'][0]['treeId'],result['treeId']);self.assertEqual(len(history['commits']),2);self.assertTrue(history['readOnly']);self.assertEqual((self.root/'main.txt').read_text(),'newer worktree\n');self.assertEqual((self.root/'.git/index').read_bytes(),before_index);s=self.snapshot();self.assertEqual({x['path']:(x['indexStatus'],x['worktreeStatus']) for x in s['entries']},{'main.txt':('.','M'),'other.txt':('M','.')})
    def test_worktree_index_and_head_drift_all_reject_without_overwrite(self):
        self.seed();self.file('main.txt','pending\n');pin=self.snapshot()['pin'];self.file('main.txt','newer\n');index=(self.root/'.git/index').read_bytes();self.assertEqual(self.run_protocol('stage',False,expected=pin,paths=['main.txt'])['code'],'git_review_stale');self.assertEqual((self.root/'.git/index').read_bytes(),index)
        pin=self.snapshot()['pin'];self.file('other.txt','other staged\n');self.git('add','--','other.txt');index=(self.root/'.git/index').read_bytes();self.assertEqual(self.run_protocol('unstage',False,expected=pin,paths=['main.txt'])['code'],'git_review_stale');self.assertEqual((self.root/'.git/index').read_bytes(),index)
        pin=self.snapshot()['pin'];self.git('commit','-m','external fixture only');head=self.git('rev-parse','HEAD');self.assertEqual(self.run_protocol('stage',False,expected=pin,paths=['main.txt'])['code'],'git_review_stale');self.assertEqual(self.git('rev-parse','HEAD'),head)
    def test_conflict_requires_original_review_then_explicit_resolution(self):
        self.seed();self.git('checkout','-b','other');self.file('main.txt','branch other\n');self.git('commit','-am','other');self.git('checkout','main');self.file('main.txt','branch main\n');self.git('commit','-am','main');self.git('merge','other',ok=(1,));s=self.snapshot();row=next(x for x in s['entries'] if x['path']=='main.txt');self.assertTrue(row['conflict']);self.assertEqual({x['stage'] for x in row['indexEntries']},{1,2,3});self.assertEqual(self.run_protocol('stage',False,expected=s['pin'],paths=['main.txt'])['code'],'git_conflict_requires_explicit_resolution')
        self.file('main.txt','owner CAS-reviewed merged text\n');s=self.snapshot();self.run_protocol('resolve',expected=s['pin'],paths=['main.txt']);self.assertFalse(any(x['conflict'] for x in self.snapshot()['entries']));r=self.commit(['main.txt'],'owner reviewed merge');self.assertEqual(len(self.git('show','-s','--format=%P',r['commitId']).split()),2);self.assertFalse((self.root/'.git/MERGE_HEAD').exists())
    def test_preexisting_index_lock_is_never_removed(self):
        self.seed();self.file('main.txt','changed\n');(self.root/'.git/index.lock').write_text('unknown lock');s=self.snapshot();self.assertTrue(s['indexLocked']);self.assertEqual(self.run_protocol('stage',False,expected=s['pin'],paths=['main.txt'])['code'],'git_index_locked');self.assertEqual((self.root/'.git/index.lock').read_text(),'unknown lock')
    def test_readonly_and_commit_do_not_execute_hooks_fsmonitor_or_external_diff(self):
        self.seed();marker=self.root/'must-not-exist';evil=self.root/'.git/evil';evil.write_text('#!/bin/sh\ntouch '+str(marker)+'\n');evil.chmod(0o700)
        self.git('config','core.fsmonitor',str(evil));self.git('config','diff.external',str(evil));self.git('config','diff.fixture.textconv',str(evil));self.file('.gitattributes','main.txt diff=fixture\n');hooks=self.root/'.git/hooks';hooks.mkdir(exist_ok=True);shutil.copyfile(evil,hooks/'pre-commit');(hooks/'pre-commit').chmod(0o700);self.file('main.txt','changed\n');s=self.snapshot();self.run_protocol('diff',expected=s['pin'],path='main.txt',layer='index_worktree');self.stage(['main.txt']);self.commit(['main.txt']);self.assertFalse(marker.exists())
    def test_filter_and_include_are_explicitly_unsupported_not_executed(self):
        self.seed();self.git('config','filter.bad.clean','touch must-not-exist');r=self.run_protocol('status',False);self.assertEqual(r['code'],'git_config_execution_unsupported');self.assertFalse((self.root/'must-not-exist').exists());self.git('config','--remove-section','filter.bad');self.git('config','include.path','/outside/secret');self.assertEqual(self.run_protocol('status',False)['code'],'git_config_execution_unsupported')
    def test_linked_gitdir_and_linked_metadata_are_not_followed(self):
        self.file('main.txt','one\n');outside=self.root/'outside';outside.mkdir();(self.root/'.git').symlink_to(outside,target_is_directory=True);self.assertEqual(self.run_protocol('status',False)['code'],'git_repository_layout_unsupported');(self.root/'.git').unlink();self.init();configuration=self.root/'.git/config';outside_config=self.root/'config-copy';os.link(configuration,outside_config);self.assertEqual(self.run_protocol('status',False)['code'],'git_metadata_link_unsupported')
    def test_wrong_reviewed_tree_or_parent_rejected_before_publication(self):
        self.seed();self.file('main.txt','changed\n');self.stage(['main.txt']);s=self.snapshot();row=next(x for x in s['entries'] if x['path']=='main.txt');entry=row['indexEntries'][0];head=self.git('rev-parse','HEAD');before=(self.root/'.git/index').read_bytes()
        common=dict(expected=s['pin'],paths=['main.txt'],message='owner review',authorName='Fixture',authorEmail='fixture@example.invalid')
        wrong=[dict(path='main.txt',mode=entry['mode'],objectId='0'*len(entry['objectId']))]
        self.assertEqual(self.run_protocol('commit',False,reviewedEntries=wrong,parents=[head],**common)['code'],'git_commit_tree_review_mismatch')
        right=[dict(path='main.txt',mode=entry['mode'],objectId=entry['objectId'])]
        self.assertEqual(self.run_protocol('commit',False,reviewedEntries=right,parents=[],**common)['code'],'git_commit_parent_review_mismatch')
        self.assertEqual(self.git('rev-parse','HEAD'),head);self.assertEqual((self.root/'.git/index').read_bytes(),before)
    def test_cache_status_names_do_not_read_cache_content(self):
        self.seed();self.file('node_modules/oversized.cache','x'*(2*1024*1024+1));s=self.snapshot();row=next(r for r in s['entries'] if r['path']=='node_modules/oversized.cache');self.assertFalse(row['sourceScope']);self.assertFalse(row['file']['contentRead'])
    def test_literal_pathspec_and_unicode_are_not_command_or_pattern_inputs(self):
        self.file('-dash file.txt','one\n');self.file('中 文/emoji😀.txt','unicode\n');self.init();self.stage(['-dash file.txt','中 文/emoji😀.txt']);self.commit(['-dash file.txt','中 文/emoji😀.txt']);self.assertEqual(self.git('show','HEAD:-dash file.txt'),'one');self.assertEqual(self.git('show','HEAD:中 文/emoji😀.txt'),'unicode');self.assertEqual(self.snapshot()['entries'],[])

if __name__=='__main__':unittest.main(verbosity=2)
