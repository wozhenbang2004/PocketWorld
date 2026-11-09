#!/usr/bin/env python3
"""Explicit public CI config adapter; never edits checkout configuration.
Reuses repository production preparation and App verifier. Only the public
bootstrap URL/SPKI inputs are overridden in this isolated Python process.
"""
import argparse
import base64
import hashlib
import importlib.util
from pathlib import Path
import sys

PIN='f869eae76eab3da1e0ab6f914da94f75b10f382e06c41338e887e06caee2d960'
MANIFEST='https://github.com/wozhenbang2004/PocketWorld/releases/latest/download/release.json'

def approved_pin(path):
 data=Path(path).read_bytes()
 digest=hashlib.sha256(data).hexdigest()
 if digest!=PIN:raise ValueError('CI public SPKI does not match approved pin')
 return base64.b64encode(data).decode('ascii'),digest

def load_tools(repo):
 spec=importlib.util.spec_from_file_location('original_windows_release',Path(repo)/'tools/windows-release.py')
 tools=importlib.util.module_from_spec(spec);spec.loader.exec_module(tools);return tools

def dispatch(tools,runtime,command,forwarded,public_der):
 pin=approved_pin(public_der)
 if command=='verify-envelope':
  original=tools.configured_pin
  try:
   # The original verifier still compiles/runs repository UpdateManifest.java.
   tools.configured_pin=lambda repo:pin
   return tools.verify_envelope(runtime,forwarded)
  finally:tools.configured_pin=original
 if command=='prepare':
  # Load original module through its existing platform/subprocess adapter.
  module=runtime.existing('prepare-update-release')
  original=module.configured_manifest_url
  try:
   module.configured_manifest_url=lambda:MANIFEST
   args=[*forwarded,'--build-tools',str(runtime.build_tools)]
   if runtime.java_home:args+=['--java-home',str(runtime.java_home)]
   with tools.arguments(module.__file__,args):return module.main()
  finally:module.configured_manifest_url=original
 raise ValueError('Unsupported CI public-config override')

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--repo',required=True,type=Path);p.add_argument('--public-key-der',required=True,type=Path)
 p.add_argument('--temp-dir',required=True,type=Path);p.add_argument('--java-home',required=True)
 p.add_argument('--build-tools',required=True);p.add_argument('--openssl',required=True)
 p.add_argument('command',choices=['verify-envelope','prepare'])
 args,forwarded=p.parse_known_args()
 tools=load_tools(args.repo)
 runtime=tools.Runtime(args.repo,args.temp_dir,args.java_home,args.build_tools,args.openssl)
 with runtime.environment():return dispatch(tools,runtime,args.command,forwarded,args.public_key_der) or 0
if __name__=='__main__':
 try:sys.exit(main())
 except Exception:
  print('CI update verification/preparation rejected input; no checkout configuration changed.',file=sys.stderr);sys.exit(1)
