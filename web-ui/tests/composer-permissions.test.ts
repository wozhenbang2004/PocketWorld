import test from 'node:test';
import assert from 'node:assert/strict';
import {composerPermissionView} from '../src/composer-permissions.ts';
const task=()=>({id:'t1',goal:'Analyze',status:'awaiting_approval',raw:{id:'t1',conversationId:'c1',actorId:'owner',spaceId:'private',kind:'assistant',runId:'r1',coordinatorEpoch:2,executionScope:{toolLimit:20,toolsUsed:4,modelCallLimit:30,modelCallsUsed:5,deadlineAt:0,executionPhase:'planning'}}});
test('permissions project only the bound native scope; do not confer new rights',()=>{
 const state={conversationId:'c1',foregroundTurn:{conversationId:'c1',taskId:'t1',runId:'r1',epoch:2},tasks:[task()]};
 const view=composerPermissionView(state);
 assert.equal(view?.needsReview,true); assert.equal(view?.toolLimit,20); assert.equal(view?.phase,'planning');
 assert.equal(view?.modelCallsUsed,5);
});
test('never select a foreign actor or stale run',()=>{
 const base=task();const state={conversationId:'c1',foregroundTurn:{conversationId:'c1',taskId:'t1',runId:'r1',epoch:2},tasks:[base]};
 base.raw.spaceId='team';assert.equal(composerPermissionView(state),null);
 base.raw.spaceId='private';state.foregroundTurn.runId='stale';assert.equal(composerPermissionView(state),null);
});
test('historical permission task must be anchored by a message in the same conversation',()=>{
 const t=task(), state={conversationId:'c1',tasks:[t],messages:[{taskId:'t1'}]};
 assert.equal(composerPermissionView(state)?.taskId,'t1');
 t.raw.conversationId='elsewhere'; assert.equal(composerPermissionView(state),null);
});
