import test from 'node:test';
import assert from 'node:assert/strict';
import {composerHasPayload} from '../src/composer-send-policy.ts';
test('attachment-only is a sendable payload and whitespace without attachments is not',()=>{
  assert.equal(composerHasPayload('',1),true);
  assert.equal(composerHasPayload(' \n ',0),false);
  assert.equal(composerHasPayload('hi',0),true);
  assert.equal(composerHasPayload('',-1),false);
});
