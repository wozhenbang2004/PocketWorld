package dev.pocketengine.features.skills;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fixed helper added to the SAME frozen native Skill copy. No installer, model tool,
 * interpreter discovery, external upload or retry. The original Program verifies its bytes. */
final class ManagedScriptCallbackClient {
    private ManagedScriptCallbackClient(){}
    static byte[] source(){return SOURCE.getBytes(StandardCharsets.UTF_8);}
    static byte[] command(List<String> bootstrap)throws Exception {
        if(bootstrap.isEmpty()||!bootstrap.get(0).startsWith("/"))throw new SecurityException("script_callback_fixed_python_missing");
        StringBuilder source=new StringBuilder("#!/bin/sh\nexec");
        for(String value:bootstrap){if(value.indexOf('\0')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new SecurityException("script_callback_fixed_python_invalid");source.append(' ').append(quote(value));}
        source.append(" -I \"${0%/*}/client.py\" \"$@\"\n");return source.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private static final String SOURCE="""
import json, os, pathlib, socket, stat, struct, sys, uuid

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('duplicate_callback_key')
        result[key] = value
    return result

def unavailable(status='unknown'):
    return {'ok':False,'accepted':None if status == 'unknown' else False,'status':status,
            'code':'script_private_output_transport_unconfirmed' if status == 'unknown' else 'script_private_output_unavailable',
            'replayAllowed':False,'externalTransmission':False,'executionAuthorized':False}

def main():
    if len(sys.argv) != 3 or sys.argv[1] != 'share': return unavailable('rejected')
    root = pathlib.Path(__file__).resolve().parent.parent
    path = root / '.native-skill-callback.json'
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > 4096: return unavailable('rejected')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        opened = os.fstat(stream.fileno())
        if (opened.st_dev,opened.st_ino,opened.st_size,opened.st_mtime_ns) != (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns): return unavailable('rejected')
        raw = stream.read(4097)
    after = path.lstat()
    if len(raw) != before.st_size or len(raw) > 4096 or (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns) != (after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns): return unavailable('rejected')
    descriptor = json.loads(raw.decode('utf-8', errors='strict'), object_pairs_hook=unique)
    if set(descriptor) != {'format','endpoint','nonce','operationId','hostUid','hostPid','peerCredentialView'} or descriptor['format'] != 'pocketworld.skill-private-output-endpoint.v1': return unavailable('rejected')
    if type(descriptor['hostUid']) is not int or descriptor['hostUid'] < 0 or type(descriptor['hostPid']) is not int or descriptor['hostPid'] <= 0: return unavailable('rejected')
    if descriptor['peerCredentialView'] not in ('native', 'proot-id0'): return unavailable('rejected')
    endpoint = descriptor['endpoint']
    if set(endpoint) != {'namespace','name'}: return unavailable('rejected')
    if endpoint['namespace'] == 'abstract': address = chr(0) + endpoint['name']
    elif endpoint['namespace'] == 'filesystem' and endpoint['name'].startswith('/'): address = endpoint['name']
    else: return unavailable('rejected')
    request_id = str(uuid.uuid4())
    request = {'format':'pocketworld.skill-private-output-request.v1','nonce':descriptor['nonce'],
               'requestId':request_id,'operationId':descriptor['operationId'],'path':sys.argv[2]}
    frame = json.dumps(request, ensure_ascii=False, separators=(',', ':')).encode('utf-8', errors='strict') + b'\\n'
    if len(frame) > 4096: return unavailable('rejected')
    # This is one attempt. A half write, missing reply, EOF or mismatched response is
    # UNKNOWN: the original durable publication may already exist, never resend it.
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as channel:
        channel.settimeout(5.0)
        channel.connect(address)
        peer = channel.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize('3i'))
        pid, uid, _ = struct.unpack('3i', peer)
        # The pinned Android PRoot -0 library also rewrites SO_PEERCRED's UID/GID
        # to guest root, even for its same-real-UID native App server; PID is not
        # rewritten. The native frozen profile selects that view, not model input.
        # Always bind to the original server PID too. Its native-side same-UID
        # admission remains a separate kernel check, unaffected by this view.
        expected_uid = 0 if descriptor['peerCredentialView'] == 'proot-id0' else descriptor['hostUid']
        if pid != descriptor['hostPid'] or uid != expected_uid: return unavailable('rejected')
        channel.sendall(frame)
        response = bytearray()
        while True:
            byte = channel.recv(1)
            if not byte: return unavailable()
            if byte == b'\\n': break
            if len(response) >= 16383: return unavailable()
            response.extend(byte)
    value = json.loads(response.decode('utf-8', errors='strict'), object_pairs_hook=unique)
    if value.get('format') != 'pocketworld.skill-private-output-reply.v1' or value.get('requestId') != request_id or value.get('operationId') != descriptor['operationId']: return unavailable()
    result = value.get('result')
    return result if isinstance(result, dict) else unavailable()

if __name__ == '__main__':
    try: result = main()
    except Exception: result = unavailable()
    print(json.dumps(result, ensure_ascii=False, separators=(',', ':')), flush=True)
    sys.exit(0 if result.get('status') == 'published' and result.get('ok') is True else 2)
""";
}
