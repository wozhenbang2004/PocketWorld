import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const composer = readFileSync(new URL('../src/library/composer.tsx', import.meta.url), 'utf8');
const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../src/composer-squircle.css', import.meta.url), 'utf8');
const scoped = readFileSync(new URL('../src/ScopedConversationModelControls.tsx', import.meta.url), 'utf8');

test('composer retains native draft submission and shows send only for available content', () => {
  assert.match(composer, /const canSend=!disabled&&!submitting&&!sendDisabled&&hasContent/);
  assert.match(composer, /\{hasContent&&\(!busy\|\|!onStop\|\|hasContent\)&&!listening&&<PressButton/);
  assert.match(composer, /onKeyDown=\{e=>\{/);
  assert.match(app, /run\('composer\.send',\{key:draftKey,epoch:draftEpoch,text\}\)/);
});

test('composer gray context lip has no invented percentage', () => {
  assert.match(composer, /measuredContext===null\?'上下文占用暂无可核对数据'/);
  assert.match(composer, /measuredContext!==null&&<span>\{measuredContext\}%<\/span>/);
  assert.match(styles, /width: 76%; max-width: 540px/);
  assert.doesNotMatch(styles, /border:\s*1px solid/);
});

test('composer controls follow add, permission, editor, model, reasoning and mic', () => {
  assert.match(app, /<IconButton icon=\{RiShieldCheckLine\}[^>]+aria-label="执行权限"/);
  assert.match(styles, /\.pw-composer-leading\s*\{[^}]*order: 1/);
  assert.match(styles, /\.pw-composer-row > textarea\s*\{[^}]*order: 2/);
  assert.match(styles, /\.pw-composer-controls\s*\{[^}]*order: 3/);
  assert.match(styles, /\.afa-toolbar\s*\{[^}]*order: 4/);
  assert.match(scoped, /RiBox3Line/);
  assert.match(scoped, /RiSparklingLine/);
});

test('continuous corners retain Android WebView fallback and reduced-motion behavior', () => {
  assert.match(styles, /@supports \(corner-shape: superellipse\(2\)\)/);
  assert.match(styles, /border-radius: 30px/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(styles, /@media \(max-width: 360px\)/);
});
