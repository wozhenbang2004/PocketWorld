# Independently authored Linux test fixture, never imported or launched by production.
# Child-subreaper is set only in this new Python process. We wait only exact native
# descendants with matching PID/birth/group/session; no process sweep or outer kill.
import argparse,ctypes,json,os,pathlib,selectors,subprocess,sys,time,uuid
args=argparse.ArgumentParser(description='Only this explicit test launcher owns/reaps its newly created JNI subtree')
args.add_argument('--mode',choices=['delayed-reap','held-through-receipt','managed-held-through-receipt','preparation-held-through-receipt'],required=True)
args.add_argument('--java',required=True);args.add_argument('--classpath',required=True);args.add_argument('--native',required=True);args.add_argument('--python',required=True);args.add_argument('--output',required=True)
options=args.parse_args();ROOT=pathlib.Path(options.output).resolve();ROOT.mkdir(parents=True,exist_ok=True)
java_command=[options.java,'-Xmx192m','-Djava.library.path='+options.native,'-cp',options.classpath,'dev.pocketengine.features.local.runtime.OwnedGroupObservationProbe']
def fact(pid):
    data=pathlib.Path('/proc/'+str(pid)+'/stat').read_text().rsplit(') ',1)[1].split()
    return dict(pid=pid,state=data[0],parent=int(data[1]),group=int(data[2]),session=int(data[3]),born=int(data[19]))
def exact(current,known):
    return all(current[k]==known[k] for k in ('pid','group','session','born'))
# Only this newly created test launcher/subtree, not the App/JVM or the agent host.
libc=ctypes.CDLL(None,use_errno=True)
if libc.prctl(36,1,0,0,0)!=0: raise OSError(ctypes.get_errno(),'own_test_subreaper_failed')
flag=ctypes.c_int()
if libc.prctl(37,ctypes.byref(flag),0,0,0)!=0 or flag.value!=1: raise RuntimeError('own_test_subreaper_not_observed')
mode=options.mode; nonce=str(uuid.uuid4()); observations=[]; scope=None; result=None; reaped=False; zombie_at=None
started=time.monotonic(); stderr=open(ROOT/(mode+'-'+nonce+'-stderr.log'),'wb')
managed=['preparation' if mode.startswith('preparation-') else 'command'] if mode.startswith(('managed-','preparation-')) else []
worker=subprocess.Popen(java_command+[str(pathlib.Path(options.python).resolve()),nonce,str(ROOT)]+managed,stdout=subprocess.PIPE,stderr=stderr,bufsize=0)
java_identity=fact(worker.pid); selector=selectors.DefaultSelector();selector.register(worker.stdout,selectors.EVENT_READ); pending=b''
try:
    while time.monotonic()-started<25:
        for _,_ in selector.select(.01):
            data=os.read(worker.stdout.fileno(),65536)
            if not data: selector.unregister(worker.stdout);continue
            pending+=data
            while b'\n' in pending:
                line,pending=pending.split(b'\n',1)
                if not line.startswith(b'{'):continue
                row=json.loads(line)
                if row.get('nonce')!=nonce:raise RuntimeError('not_our_original_probe')
                if row['event']=='owned_scope':
                    if scope is not None:raise RuntimeError('duplicate_owned_scope')
                    scope=row
                    if row['owner']['parent']!=worker.pid or row['leader']['parent']!=row['owner']['pid'] or row['leader']['group']!=row['leader']['pid'] or row['leader']['session']!=row['leader']['pid']:raise RuntimeError('original_native_owner_and_session_not_observed')
                    if not exact(fact(row['owner']['pid']),row['owner']):raise RuntimeError('original_private_owner_identity_changed')
                    actual=fact(row['child']['pid'])
                    if not exact(actual,row['child']) or actual['parent'] not in (row['leader']['pid'],os.getpid()):raise RuntimeError('child_not_our_original_subtree')
                    observations.append(dict(event='exact_child_observed',fact=actual,elapsedMillis=int((time.monotonic()-started)*1000)))
                elif row['event']=='native_exit':result=row
        if scope is not None and not reaped:
            actual=fact(scope['child']['pid'])
            if not exact(actual,scope['child']):raise RuntimeError('original_child_identity_changed')
            if actual['state']=='Z' and actual['parent']==os.getpid():
                if zombie_at is None:zombie_at=time.monotonic();observations.append(dict(event='own_orphan_zombie_observed',fact=actual,elapsedMillis=int((zombie_at-started)*1000)))
                due=(mode=='delayed-reap' and time.monotonic()-zombie_at>=.4) or (mode.endswith('held-through-receipt') and result is not None)
                if due:
                    pid,status=os.waitpid(scope['child']['pid'],0)
                    if pid!=scope['child']['pid']:raise RuntimeError('wrong_original_child_reaped')
                    reaped=True;observations.append(dict(event='exact_own_child_reaped',pid=pid,status=status,elapsedMillis=int((time.monotonic()-started)*1000)))
        if result is not None and reaped and worker.poll() is not None:break
    if result is None or not reaped or worker.wait(timeout=5)!=0:raise RuntimeError('owned_probe_did_not_settle')
    record=dict(mode=mode,nonce=nonce,launcherPid=os.getpid(),javaIdentity=java_identity,scope=scope,observations=observations,receipt=result,allCreatedProcessesReaped=True,scopePolicy='only exact created parent/child identities; no proc sweep and no outer signalling')
    (ROOT/(mode+'-'+nonce+'-result.json')).write_text(json.dumps(record,indent=2)+'\n'); print(json.dumps(record))
finally:
    selector.close();stderr.close()
    # The native entry owns its exact group stop. Never guess/signal an unknown PID.
    if worker.poll() is None:worker.wait(timeout=20)
