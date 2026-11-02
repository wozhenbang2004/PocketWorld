"""Self-authored HTTPS/smart-Git fixture, loopback only, no third-party repo.

The private fixture CA is selected by a fixture-only Git wrapper in its private
RuntimeTool directory. It does NOT change the machine/Android trust store or
the production helper's sslVerify=true policy. Never use it for external hosts.
"""
import base64
import http.server
import json
import os
from pathlib import Path
import shlex
import ssl
import subprocess
import sys
import threading
from urllib.parse import urlsplit

root = Path(sys.argv[1]).resolve()
git = Path(sys.argv[2]).resolve()
mode = sys.argv[3]
root.mkdir()
home = root / 'fixture-home'
home.mkdir()
environment = {'PATH': '/usr/bin:/bin', 'HOME': str(home), 'GIT_CONFIG_NOSYSTEM': '1',
               'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_AUTHOR_NAME': 'Own Fixture',
               'GIT_COMMITTER_NAME': 'Own Fixture', 'GIT_AUTHOR_EMAIL': 'fixture@example.invalid',
               'GIT_COMMITTER_EMAIL': 'fixture@example.invalid', 'LANG': 'C.UTF-8'}

def run(arguments, cwd=root):
    result = subprocess.run([str(git), '-c', 'core.hooksPath=/dev/null'] + arguments,
                            cwd=cwd, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            check=False, timeout=15)
    if result.returncode:
        raise RuntimeError('self-owned fixture Git setup failed')
    return result.stdout.decode('utf-8').strip()

repo = root / 'source'
run(['init', '--quiet', '--initial-branch=mobile', str(repo)])
(repo / 'main.py').write_text("print('first self-owned revision')\n", encoding='utf-8')
(repo / 'binary.dat').write_bytes(bytes([0, 255, 128, 10, 0, 2]))
(repo / 'bin').mkdir()
(repo / 'bin/check.sh').write_text("#!/bin/sh\nprintf 'self-owned-only\\n'\n", encoding='utf-8')
os.chmod(repo / 'bin/check.sh', 0o755)
(repo / '中文.txt').write_text('明确示例源码\n', encoding='utf-8')
if mode == 'symlink':
    (repo / 'unsupported-link').symlink_to('main.py')
elif mode == 'lfs':
    (repo / 'large.dat').write_text('version https://git-lfs.github.com/spec/v1\noid sha256:' + 'a' * 64 + '\nsize 12\n')
run(['add', '--all'], repo)
run(['commit', '--quiet', '-m', 'first fixture version'], repo)
first = run(['rev-parse', 'HEAD'], repo)
(repo / 'main.py').write_text("print('second self-owned revision')\n", encoding='utf-8')
run(['commit', '--quiet', '-am', 'second fixture version'], repo)
head = run(['rev-parse', 'HEAD'], repo)
bare = root / 'repository.git'
run(['clone', '--quiet', '--bare', '--no-hardlinks', str(repo), str(bare)])
run(['config', 'http.receivepack', 'false'], bare)
certificate, key = root / 'fixture-ca.pem', root / 'fixture-key.pem'
created = subprocess.run(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt',
                          'ec_paramgen_curve:prime256v1', '-nodes', '-days', '1', '-subj',
                          '/CN=pw-owned-fixture.invalid', '-addext',
                          'subjectAltName=DNS:pw-owned-fixture.invalid', '-keyout', str(key),
                          '-out', str(certificate)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         check=False, timeout=15)
if created.returncode:
    raise RuntimeError('fixture TLS certificate creation failed')
wrapper = root / 'git-with-owned-fixture-ca'
username, token = 'fixture-only-user', 'fixture-only-token-not-a-real-secret'
expected_auth = 'Basic ' + base64.b64encode((username + ':' + token).encode()).decode()
wrapper.write_text('#!' + sys.executable + '\n' +
    'import os,sys,json,fcntl\n' +
    'token=' + repr(token) + '\nauth=' + repr(expected_auth) + '\n' +
    'fdpath=os.environ.get("GIT_CONFIG_GLOBAL", "")\n' +
    'fact={"secretInEnvironment":any(token in value or auth in value for value in os.environ.values()),"secretInArgv":any(token in value or auth in value for value in sys.argv),"credentialFd":fdpath.startswith("/proc/self/fd/")}\n' +
    'if fact["credentialFd"]:\n fd=int(fdpath.rsplit("/",1)[1]);fact["credentialFdReadOnly"]=fcntl.fcntl(fd,fcntl.F_GETFL)&os.O_ACCMODE==os.O_RDONLY;fact["credentialFdSealed"]=fcntl.fcntl(fd,getattr(fcntl,"F_GET_SEALS",1034))&15==15\n' +
    'with open(' + repr(str(root / 'git-environment-facts.jsonl')) + ',"a") as out:out.write(json.dumps(fact)+"\\n")\n' +
    'os.execv(' + repr(str(git)) + ',[' + repr(str(git)) + ',"-c",' + repr('http.sslCAInfo=' + str(certificate)) + ']+sys.argv[1:])\n')
os.chmod(wrapper, 0o700)
stopping = threading.Event()
guard = threading.Lock()
requests = []
children = set()

def persist():
    with guard:
        (root / 'observations.json').write_text(json.dumps({'requests': list(requests), 'mode': mode}))

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *arguments):
        pass
    def do_GET(self):
        self.respond()
    def do_POST(self):
        self.respond()
    def respond(self):
        authenticated = self.headers.get('Authorization') == expected_auth
        with guard:
            requests.append({'method': self.command, 'path': self.path, 'authenticated': authenticated})
        persist()
        if mode == 'redirect':
            self.send_response(302)
            self.send_header('Location', 'https://not-the-reviewed-origin.invalid/elsewhere')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if mode in ('auth', 'reject-reflect') and (not authenticated or mode == 'reject-reflect'):
            # Intentionally reflect the synthetic fixture token. Production
            # helper must discard raw provider error bodies/stderr from logs.
            body = ('fixture rejects ' + token).encode()
            self.send_response(401)
            self.send_header('WWW-Authenticate', 'Basic realm="self-owned-fixture"')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if mode == 'slow' and self.command == 'POST':
            (root / 'upload-waiting').write_text('original request arrived; no credentials')
            while not stopping.wait(.05):
                if (root / 'release-upload').exists():
                    break
        if stopping.is_set():
            return
        parsed = urlsplit(self.path)
        if not parsed.path.startswith('/repository.git/'):
            self.send_error(404)
            return
        size = int(self.headers.get('Content-Length', '0'))
        if size < 0 or size > 4 * 1024 * 1024:
            self.send_error(413)
            return
        body = self.rfile.read(size)
        env = dict(environment, GIT_PROJECT_ROOT=str(root), GIT_HTTP_EXPORT_ALL='1',
                   REQUEST_METHOD=self.command, PATH_INFO=parsed.path, QUERY_STRING=parsed.query,
                   CONTENT_TYPE=self.headers.get('Content-Type', ''), CONTENT_LENGTH=str(len(body)),
                   SERVER_PROTOCOL='HTTP/1.1', REMOTE_ADDR='127.0.0.1')
        if authenticated:
            env['REMOTE_USER'] = 'fixture-only-user'
        child = subprocess.Popen([str(git), 'http-backend'], cwd=root, env=env,
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        with guard:
            children.add(child)
        try:
            output, error = child.communicate(body, timeout=15)
        finally:
            if child.poll() is None:
                child.terminate()
                child.communicate(timeout=3)
            with guard:
                children.discard(child)
        header, payload = output.split(b'\r\n\r\n', 1)
        status = 200
        fields = []
        for line in header.split(b'\r\n'):
            name, value = line.decode('ascii').split(':', 1)
            if name.lower() == 'status':
                status = int(value.strip().split(' ', 1)[0])
            else:
                fields.append((name, value.strip()))
        self.send_response(status)
        for name, value in fields:
            self.send_header(name, value)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(certificate, key)
server.socket = context.wrap_socket(server.socket, server_side=True)
thread = threading.Thread(target=server.serve_forever, name='fixture-https', daemon=True)
thread.start()
print(json.dumps({'port': server.server_address[1], 'gitWrapper': str(wrapper), 'firstCommit': first,
                  'headCommit': head, 'username': username, 'token': token, 'gitVersion': run(['--version'])}), flush=True)
try:
    sys.stdin.buffer.read()
finally:
    stopping.set()
    server.shutdown()
    server.server_close()
    thread.join(timeout=3)
    with guard:
        owned = list(children)
    for child in owned:
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=3)
    persist()
