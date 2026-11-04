import { build } from 'esbuild';
import { pathToFileURL } from 'node:url';
import fs from 'node:fs/promises';
const source = `
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import assert from 'node:assert/strict';
import BootstrapWorkspace from './src/BootstrapWorkspace';
const first={surface:'bootstrap',revision:2,generation:1,state:'failed',stage:'migrated',activity:'features',module:'memory',recovery:'failed',installedVersion:'test (1)',requiredSchema:10,observedSchema:10,retryAvailable:false,optionalUnavailable:[],maintenance:null,reason:{code:'initialization_failed',message:'原初始化或恢复尚未完成',nextStep:'旧提示'}};
function render(view){return renderToStaticMarkup(<BootstrapWorkspace snapshot={{bootstrap:view,state:{},composer:null,capabilities:{commands:['bootstrap.read']},host:{ready:true}}}/>);}
let html=render(first);assert.match(html,/启动准备未完成/);assert.match(html,/准备工作能力/);assert.match(html,/记忆/);assert.doesNotMatch(html,/原初始化或恢复尚未完成/);assert.doesNotMatch(html,/原任务核对尚未完成/);assert.doesNotMatch(html,/>重新核对并启动</);
html=render({...first,reason:{code:'storage_full',message:'本机空间不足',nextStep:'释放本机空间后重试'}});assert.match(html,/本机空间不足/);assert.match(html,/释放本机空间后重试/);
html=render({...first});assert.doesNotMatch(html,/首次/);
console.log('PASS: first launch copy, exact activity, closed retry, known-error guidance and no first-install assumption');
`;
await build({stdin:{contents:source,resolveDir:process.cwd(),sourcefile:'first-launch.tsx',loader:'tsx'},bundle:true,platform:'node',format:'esm',packages:'external',loader:{'.css':'empty'},outfile:'first-launch-render.mjs',banner:{js:'globalThis.window = {};'}});
try { await import(pathToFileURL(process.cwd()+'/first-launch-render.mjs')); } finally { await fs.unlink('first-launch-render.mjs'); }
