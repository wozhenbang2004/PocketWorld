#!/usr/bin/env python3
"""Exercise the ACTUAL fixed client without any kernel socket or publication.

Only transport calls are mocked. Descriptors are real own regular files; every
reply is explicitly rejected or absent, never a fabricated published artifact.
This is not an AF_UNIX/PRoot/Android/Book substitute or an alternate bridge.
"""
import contextlib
import io
import json
import pathlib
import runpy
import socket
import struct
import sys
from unittest.mock import patch

client = pathlib.Path(sys.argv[1])
case = sys.argv[2]
assert case in {'native_peer', 'proot_peer', 'wrong_uid', 'wrong_pid', 'proot_wrong_uid', 'proot_wrong_pid', 'half_write', 'lost_reply'}
uid, pid = 41042, 12345
operation = '7fd6f9cf-4f22-4e63-b722-69ec903f39dc'
nonce = 'x' * 43
# Each frozen client is paired with its native descriptor generation. This is
# versioned input compatibility, not a changed expected outcome or a grant.
new_descriptor = 'peerCredentialView' in client.read_text(encoding='utf-8')
descriptor = {'format': 'pocketworld.skill-private-output-endpoint.v1',
              'endpoint': {'namespace': 'filesystem', 'name': '/own-protocol-fixture-no-socket'},
              'nonce': nonce, 'operationId': operation, 'hostUid': uid}
if new_descriptor:
    descriptor.update(hostPid=pid, peerCredentialView='proot-id0' if case.startswith('proot_') else 'native')
(client.parent.parent / '.native-skill-callback.json').write_text(json.dumps(descriptor), encoding='utf-8')
attempts = connects = sends = closes = 0
response = bytearray()

class Channel:
    def __enter__(self): return self
    def __exit__(self, *_):
        global closes
        closes += 1
    def settimeout(self, seconds): assert 0 < seconds <= 5
    def connect(self, address):
        global connects
        assert address == descriptor['endpoint']['name']
        connects += 1
    def getsockopt(self, level, option, length):
        assert level == socket.SOL_SOCKET and option == socket.SO_PEERCRED and length == 12
        visible_uid = uid if case == 'proot_wrong_uid' else 0 if case.startswith('proot_') else uid + 1 if case == 'wrong_uid' else uid
        return struct.pack('3i', pid + 1 if case in {'wrong_pid', 'proot_wrong_pid'} else pid, visible_uid, 0)
    def sendall(self, frame):
        global sends, response
        sends += 1
        assert sends == 1 and len(frame) <= 4096 and frame.endswith(b'\n') and frame.count(b'\n') == 1
        request = json.loads(frame.decode('utf-8'))
        assert set(request) == {'format', 'nonce', 'requestId', 'operationId', 'path'}
        assert request['nonce'] == nonce and request['operationId'] == operation
        assert request['path'] == 'output/正文🙂.bin'
        if case == 'half_write': raise OSError('own partial-ticket simulation')
        if case == 'lost_reply': return
        reply = {'format': 'pocketworld.skill-private-output-reply.v1',
                 'requestId': request['requestId'], 'operationId': operation,
                 'result': {'ok': False, 'status': 'rejected', 'code': 'own_protocol_fixture_server_rejected',
                            'message': '协议正文🙂', 'replayAllowed': False, 'externalTransmission': False}}
        response = bytearray(json.dumps(reply, ensure_ascii=False).encode('utf-8') + b'\n')
    def recv(self, length):
        assert length == 1
        if not response: return b''
        byte = bytes(response[:1]); del response[:1]; return byte

def channel(family, kind):
    global attempts
    assert family == socket.AF_UNIX and kind == socket.SOCK_STREAM
    attempts += 1
    assert attempts == 1
    return Channel()

output = io.StringIO()
with patch.object(socket, 'socket', channel), contextlib.redirect_stdout(output):
    sys.argv = [str(client), 'share', 'output/正文🙂.bin']
    try:
        runpy.run_path(str(client), run_name='__main__')
        raise AssertionError('fixed client did not report its exit status')
    except SystemExit as ended:
        exit_code = ended.code
text = output.getvalue()
result = json.loads(text)
assert nonce not in text and descriptor['endpoint']['name'] not in text
print(json.dumps({'case': case, 'clientExit': exit_code, 'result': result,
                  'attempts': attempts, 'connects': connects, 'sends': sends, 'closes': closes,
                  'descriptorGeneration': 'current' if new_descriptor else 'legacy',
                  'kernelSocketOpened': False, 'artifactPublished': False}, ensure_ascii=False))
