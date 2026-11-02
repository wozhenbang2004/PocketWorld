package dev.pocketengine.features.skills;

/** Fixed CLI transport; all action authority remains in the original native Runner callback. */
final class ManagedScriptBridgeClient {
    private ManagedScriptBridgeClient(){}
    static String source(){return """
import json, os, socket, sys, uuid
if len(sys.argv) < 2: raise SystemExit('usage: python $POCKETWORLD_BRIDGE_CLIENT info|progress|share|phone ...')
method = sys.argv[1]
request = {'token':os.environ['POCKETWORLD_BRIDGE_TOKEN'],'requestId':uuid.uuid4().hex,'method':method}
if method == 'progress' and len(sys.argv) == 3: request['text'] = sys.argv[2]
elif method == 'share' and len(sys.argv) == 3: request['path'] = sys.argv[2]
elif method == 'phone' and len(sys.argv) == 4: request.update({'tool':sys.argv[2],'args':json.loads(sys.argv[3])})
elif method != 'info' or len(sys.argv) != 2: raise SystemExit('invalid bridge arguments')
wire = (json.dumps(request,ensure_ascii=False,separators=(',',':'))+'\\n').encode('utf-8')
if len(wire) > 16384: raise SystemExit('bridge request too large')
with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
    client.settimeout(15)
    client.connect('\\0'+os.environ['POCKETWORLD_BRIDGE_NAME'])
    client.sendall(wire)
    data = bytearray()
    while True:
        page = client.recv(1024)
        if not page: raise SystemExit('bridge disconnected; do not replay a phone action')
        data.extend(page)
        if len(data) > 131072: raise SystemExit('bridge response too large')
        if b'\\n' in data: break
    response = json.loads(bytes(data).split(b'\\n',1)[0].decode('utf-8',errors='strict'))
    print(json.dumps(response,ensure_ascii=False,separators=(',',':')))
    if not response.get('ok',True): raise SystemExit(2)
""";}
}
