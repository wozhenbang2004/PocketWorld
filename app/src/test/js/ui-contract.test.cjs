// Exercises retained compatibility UI logic directly from its shipped source; no DOM/render claim.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {webcrypto}=require('node:crypto');
const html=fs.readFileSync(path.join(__dirname,'../../main/assets/index.html'),'utf8');
function between(start,end){const at=html.indexOf(start);assert.ok(at>=0,start);const stop=html.indexOf(end,at+start.length);assert.ok(stop>at,end);return html.slice(at,stop);}
const admission=between('function displayReady(){','function effectiveTaskMode');
const canRequest=snapshot=>new Function('data',admission+';return displayReady();')(()=>snapshot);
assert.equal(canRequest({helperConnected:true,supportedAndroid:true,sessionActive:false}),true,'Service must be able to create a display at task start.');
assert.equal(canRequest({helperConnected:false,supportedAndroid:true,sessionActive:true,displayId:8}),false,'Old display metadata is insufficient without a live helper.');
assert.equal(canRequest({helperConnected:true,supportedAndroid:true,releasePending:true}),false);
assert.equal(canRequest({helperConnected:true,supportedAndroid:true,recoveryRequired:true}),false);
assert.equal(canRequest({helperConnected:true,supportedAndroid:false}),false);
const generator=between('function generateToken(){','async function copyText');
const token=()=>new Function('window',generator+';return generateToken();')({crypto:webcrypto});
const tokens=Array.from({length:50},token);assert.ok(tokens.every(v=>/^[A-Za-z0-9_-]{64}$/.test(v)));assert.equal(new Set(tokens).size,tokens.length);
assert.throws(()=>new Function('window',generator+';return generateToken();')({}),/随机令牌/);
const serializer=between('function json(value){','function notice');
const safeJson=new Function(serializer+';return json(arguments[0]);');
const rendered=safeJson({token:'private-token',nested:{apiKey:'private-key'},imageBase64:'private-image',message:'kept'});
assert.ok(!rendered.includes('private-token')&&!rendered.includes('private-key')&&!rendered.includes('private-image'));
assert.ok(rendered.includes('kept'));
assert.match(html,/phoneMode='background'/);
assert.match(html,/automation_goal/);assert.match(html,/feature_result/);assert.match(html,/speech_result/);
console.log('Compatibility UI contract checks passed: background admission, local tokens, redaction, native event hooks. No rendered UI test.');
