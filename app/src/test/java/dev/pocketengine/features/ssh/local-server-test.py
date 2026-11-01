#!/usr/bin/env python3
"""Run production transport tests against an isolated loopback OpenSSH daemon.

Keys/accounts are test fixtures. No user SSH config, keys, passwords or existing
daemon is changed. This validates desktop JVM protocol behavior, not Android JCA.
"""
from pathlib import Path
import os
import shutil
import socket
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[8]
homes = [Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))]
cache = next((home/'caches/modules-2/files-2.1' for home in homes
              if (home/'caches/modules-2/files-2.1/com.github.mwiede/jsch/2.28.8').is_dir()), None)
if cache is None:
    raise SystemExit('Set GRADLE_USER_HOME to the cache containing the declared JSch/JUnit dependencies.')
java = Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).resolve().parent
sshd = shutil.which('sshd')
keygen = shutil.which('ssh-keygen')
if not sshd or not keygen:
    raise SystemExit('Real SSH tests unavailable: install/configure an OpenSSH test daemon first.')
artifacts = [next((cache/path).rglob('*.jar')) for path in (
    'com.github.mwiede/jsch/2.28.8', 'org.json/json/20240303',
    'junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3')]
with tempfile.TemporaryDirectory(prefix='pocketworld-real-ssh-') as temporary:
    fixture = Path(temporary)
    workspace = fixture/'workspace';workspace.mkdir();(workspace/'document.txt').write_text('initial')
    outside = fixture/'outside.txt';outside.write_text('outside');(workspace/'linked.txt').symlink_to(outside)
    for name, options in [('host', ['-t','ecdsa','-b','256']),('other-host', ['-t','ecdsa','-b','256']),('identity', ['-t','rsa','-b','3072','-m','PEM'])]:
        subprocess.run([keygen, '-q', *options, '-N', '', '-f', str(fixture/name)], check=True)
    (fixture/'authorized_keys').write_text((fixture/'identity.pub').read_text())
    user = subprocess.run(['id','-un'],capture_output=True,text=True,check=True).stdout.strip()
    with socket.socket() as port_picker:
        port_picker.bind(('127.0.0.1',0));port=port_picker.getsockname()[1]
    configuration = fixture/'sshd_config'
    configuration.write_text(f'''Port {port}
ListenAddress 127.0.0.1
HostKey {fixture/'host'}
PidFile {fixture/'sshd.pid'}
AuthorizedKeysFile {fixture/'authorized_keys'}
PermitRootLogin yes
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
StrictModes no
AllowUsers {user}
AllowTcpForwarding no
AllowAgentForwarding no
X11Forwarding no
PrintMotd no
UseDNS no
LogLevel ERROR
Subsystem sftp internal-sftp
''')
    runtime = Path('/run/sshd');made_runtime=not runtime.exists()
    if made_runtime: runtime.mkdir(mode=0o755,parents=True)
    process = subprocess.Popen([sshd,'-D','-e','-f',str(configuration)],stdout=subprocess.DEVNULL,stderr=(fixture/'sshd.log').open('wb'))
    try:
        until=time.monotonic()+5
        while True:
            if process.poll() is not None: raise RuntimeError('Isolated sshd failed: '+(fixture/'sshd.log').read_text())
            try:
                with socket.create_connection(('127.0.0.1',port),timeout=.2): break
            except OSError:
                if time.monotonic()>until: raise RuntimeError('Isolated sshd did not become available')
                time.sleep(.05)
        classes=fixture/'classes';classes.mkdir()
        sources = [root/'app/src/main/java/dev/pocketengine/core/RunControl.java',
                   *[root/'app/src/main/java/dev/pocketengine/features/ssh'/name for name in ('SshProfile.java','SshCredentials.java','PinnedHostKeys.java','DirectSshClient.java','SshEnvironmentDoctor.java')],
                   *[root/'app/src/test/java/dev/pocketengine/features/ssh'/name for name in ('SshPolicyTest.java','SshLocalServerTest.java')]]
        classpath=':'.join(map(str,artifacts))
        subprocess.run([str(java/'javac'),'--release','17','-cp',classpath,'-d',str(classes),*map(str,sources)],check=True)
        subprocess.run([str(java/'java'),f'-Dpocket.ssh.port={port}',f'-Dpocket.ssh.user={user}',
                        f'-Dpocket.ssh.workspace={workspace}',f'-Dpocket.ssh.key={fixture/"identity"}',
                        f'-Dpocket.ssh.otherHostPublic={fixture/"other-host.pub"}',
                        '-cp',str(classes)+':'+classpath,'org.junit.runner.JUnitCore',
                        'dev.pocketengine.features.ssh.SshPolicyTest','dev.pocketengine.features.ssh.SshLocalServerTest'],check=True)
    except Exception:
        print('Isolated OpenSSH diagnostics (fixture server only):')
        print((fixture/'sshd.log').read_text())
        raise
    finally:
        process.terminate()
        try: process.wait(timeout=3)
        except subprocess.TimeoutExpired: process.kill();process.wait()
        # The cancellation scenario intentionally cannot promise remote termination;
        # its bounded fixture command exits after two seconds without further writes.
        time.sleep(2.1)
        if made_runtime:
            try: runtime.rmdir()
            except OSError: pass
