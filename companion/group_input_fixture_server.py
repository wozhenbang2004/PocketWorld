"""Controlled original Companion/CLI plus actual binary files; no real account/transport fake."""
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading
from pocket_companion import CompanionHandler, CompanionServer, JobManager
from test_group_input_dispatch import GROUP_PEER


def main():
    root = Path(sys.argv[1]); root.mkdir(exist_ok=True)
    workspace = root / 'workspace'; workspace.mkdir()
    protocol = root / 'protocol'; protocol.mkdir()
    home = root / 'codex-home'; home.mkdir()
    (home / 'auth.json').write_text(json.dumps({'OPENAI_API_KEY': 'synthetic-group-native-never-real-api-key'}))
    (home / 'config.toml').write_text('cli_auth_credentials_store="file"\nmodel_provider="openai"\n')
    peer_text = GROUP_PEER.replace('  text=p[\'input\'][0][\'text\']', '''  text=p['input'][0]['text']
  if 'Owner explicitly supplied these exact local input files' in text:
   import re,hashlib
   selected=re.findall(r'(\\.pocketworld-inputs/[a-f0-9-]{36}/[0-7]-[^\\n]+?) \\(\\d+ bytes; SHA-256 ([a-f0-9]{64})\\)',text)
   assert len(selected)==1,text
   actual=Path(selected[0][0]).read_bytes();assert hashlib.sha256(actual).hexdigest()==selected[0][1]
   (home/'actual-input.bin').write_bytes(actual)
   (home/'actual-input-read.json').write_text(json.dumps({'bytes':len(actual),'sha256':hashlib.sha256(actual).hexdigest(),'path':selected[0][0]}))''')
    peer = root / 'controlled-group-codex.py'; peer.write_text(peer_text)
    binary = root / 'bin'; binary.mkdir()
    wrapper = binary / 'codex'; wrapper.write_text('#!' + sys.executable + '\nimport os,sys\nos.execv(' + repr(sys.executable) + ',[' + repr(sys.executable) + ',' + repr(str(peer)) + ',' + repr(str(protocol)) + ',*sys.argv[1:]])\n'); wrapper.chmod(0o700)
    os.environ['PATH'] = str(binary) + os.pathsep + os.environ['PATH']
    token = 'native-assignment-fixture-token-longer-than-32-characters'
    manager = JobManager({'stateDir': str(root/'state'), 'environments': [{'id':'group-workspace','kind':'local','root':str(workspace),'workers':['codex_app_server'],'writeAllowed':True,'codexHome':str(home),'codexProviderVariables':[]}]}, token)
    def count(name):
        file=root/name;file.write_text(str(int(file.read_text())+1 if file.exists() else 1))
    class Handler(CompanionHandler):
        def do_POST(self):
            if self.path == '/v1/jobs': count('create-count')
            elif self.path.endswith('/start-with-inputs'): count('start-count')
            elif self.path.endswith('/cancel'): count('cancel-count')
            super().do_POST()
        def do_PUT(self):
            if '/inputs/' in self.path: count('upload-count')
            super().do_PUT()
        def _send(self,status,value):
            marker=root/'drop-upload-ack'
            if self.command=='PUT' and '/inputs/' in self.path and status==200 and marker.exists():
                marker.unlink();self.close_connection=True;self.connection.shutdown(socket.SHUT_RDWR);return
            super()._send(status,value)
    server=CompanionServer(('127.0.0.1',0),manager);server.RequestHandlerClass=Handler
    signal.signal(signal.SIGTERM,lambda *_:threading.Thread(target=server.shutdown,daemon=True).start())
    print(json.dumps({'baseUrl':'http://127.0.0.1:'+str(server.server_port),'workspace':str(workspace)}),flush=True)
    try:server.serve_forever(poll_interval=.02)
    finally:
        server.server_close();manager.close()
        (root/'resource-release.json').write_text(json.dumps({'listenerClosed':True,'activeProcesses':len(manager.processes),'activeDrivers':len(manager.drivers),'resources':len(manager.resources),'syntheticProtocolOnly':True}))


if __name__=='__main__':main()
