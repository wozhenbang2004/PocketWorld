package dev.pocketengine.features.plugins;

import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.charset.StandardCharsets;

/** Fixed native adapter. Preparation and the final server share the original managed pipe Job,
 * process group, deadline and receipt. It never installs a package manager or runs package scripts. */
public final class McpLocalPackageProgram {
    private McpLocalPackageProgram() {}
    public static String source(){return SOURCE;}
    public static String identity()throws Exception{return ProjectSources.sha256(SOURCE.getBytes(StandardCharsets.UTF_8));}
    private static final String SOURCE="""
import hashlib,json,os,pathlib,stat,subprocess,sys
root=pathlib.Path.cwd()
def raw(path,maximum):
    before=path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_size>maximum or before.st_nlink!=1: raise ValueError('mcp_package_regular_file_required')
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as f: data=f.read(maximum+1)
    after=path.lstat()
    if len(data)>maximum or (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns): raise ValueError('mcp_package_source_changed')
    return data
data=raw(root/'.native-mcp-package.json',524288)
if len(sys.argv)!=2 or hashlib.sha256(data).hexdigest()!=sys.argv[1]: raise ValueError('mcp_package_job_changed')
job=json.loads(data.decode('utf-8'));plan=job['plan'];tools=job['tools']
if job['format']!='pocketworld.native-mcp-package-job.v1' or plan['manager'] not in ('npm','pnpm'): raise ValueError('mcp_package_contract_required')
def tool_current(argv,digest):
    path=pathlib.Path(argv[0]).resolve(strict=True);before=path.stat();hashed=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(65536),b''): hashed.update(block)
    after=path.stat()
    if (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns) or hashed.hexdigest()!=digest: raise ValueError('mcp_package_tool_changed')
def source_current():
    for item in job['files']:
        path=root
        for part in item['path'].split('/'):
            if part in ('','..','.') or '\\\\' in part: raise ValueError('mcp_package_relative_source_required')
            path=path/part
            if path.is_symlink(): raise ValueError('mcp_package_link_rejected')
        value=raw(path,2097152)
        if len(value)!=item['bytes'] or hashlib.sha256(value).hexdigest()!=item['sha256']: raise ValueError('mcp_package_source_changed')
source_current()
for name in ('.native-mcp-home','.native-mcp-tmp','.native-mcp-cache'):
    (root/name).mkdir(mode=0o700)
env={'PATH':tools['path'],'LANG':'C.UTF-8','HOME':str(root/'.native-mcp-home'),'TMPDIR':str(root/'.native-mcp-tmp'),'CI':'true','NO_COLOR':'1','NPM_CONFIG_USERCONFIG':str(root/'.native-mcp-home/user.npmrc'),'NPM_CONFIG_GLOBALCONFIG':str(root/'.native-mcp-home/global.npmrc'),'COREPACK_ENABLE_PROJECT_SPEC':'0','COREPACK_ENABLE_NETWORK':'0'}
manager=tools['managerArgv']
tool_current(manager,tools['managerSha256']);tool_current(tools['nodeArgv'],tools['nodeSha256'])
probe=subprocess.run(manager+['--version'],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=sys.stderr,env=env,timeout=10,check=True)
if probe.stdout.decode('utf-8',errors='strict').strip()!=plan['managerVersion']: raise ValueError('mcp_package_manager_version_changed')
if plan['manager']=='npm':
    args=['ci','--ignore-scripts','--no-audit','--no-fund','--workspaces=false','--cache',str(root/'.native-mcp-cache')]
else:
    args=['install','--frozen-lockfile','--ignore-scripts','--ignore-pnpmfile','--config.manage-package-manager-versions=false','--config.package-manager-strict-version=true','--config.side-effects-cache=false','--config.verify-store-integrity=true','--config.recursive-install=false','--store-dir',str(root/'.native-mcp-cache')]
args+=['--registry='+plan['registry']]
print('PocketWorld: preparing the reviewed MCP dependency lock',file=sys.stderr,flush=True)
completed=subprocess.run(manager+args,stdin=subprocess.DEVNULL,stdout=sys.stderr,stderr=sys.stderr,env=env,timeout=90)
if completed.returncode: raise SystemExit(completed.returncode if completed.returncode>0 else 1)
source_current()
tool_current(tools['nodeArgv'],tools['nodeSha256'])
print('PocketWorld: dependency preparation exited 0; starting the reviewed MCP server',file=sys.stderr,flush=True)
command=tools['nodeArgv']+job['serverArgv']
os.execvpe(command[0],command,env)
""";
}
