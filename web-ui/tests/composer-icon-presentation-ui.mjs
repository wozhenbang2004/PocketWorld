/** CSS regression using BoardUI Button's label-span DOM contract.
 * Tests presentation and hit targets only, not React state, native permissions,
 * model requests, context accounting or Android installation.
 * Run: node tests/composer-icon-presentation-ui.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
const { chromium } = await import(process.env.PW_PLAYWRIGHT_MODULE || '@playwright/test');
const root = path.resolve(import.meta.dirname, '..');
const styles = await fs.readFile(process.env.PW_COMPOSER_CSS || path.join(root, 'src/composer-squircle.css'), 'utf8');
const borderless = await fs.readFile(path.join(root, 'src/borderless-surfaces.css'), 'utf8');
const output = process.env.PW_COMPOSER_EVIDENCE || path.resolve(root, '../build-artifacts/composer-icon-presentation');
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {}) });
const checks = [];
const icon = (name) => `<svg aria-hidden="true" data-icon="${name}" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="${name === 'model' ? 'm12 3 8 5v9l-8 4-8-4V8Zm0 9 8-4m-8 4-8-4m8 4v9' : name === 'reasoning' ? 'm12 2 2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4Z' : name === 'mic' ? 'M9 4a3 3 0 0 1 6 0v8a3 3 0 0 1-6 0ZM5 10v2a7 7 0 0 0 14 0v-2M12 19v3' : 'M4 12h16M12 4v16'}"/></svg>`;
const chip = (kind, label, locked) => `<button class="pw-conversation-${kind === 'model' ? 'model' : 'reasoning'}-chip" aria-label="${label}" ${locked ? 'disabled' : ''}><span>${icon(kind)}<span>${label}</span><b>高</b>${icon('chevron')}</span></button>`;
function documentFor(theme, state) {
  const locked = state === 'disabled';
  return `<!doctype html><html class="${theme === 'dark' ? 'dark' : ''}"><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
  *{box-sizing:border-box}body{margin:0;padding:24px 12px;font:15px sans-serif;background:var(--pw-canvas);color:var(--pw-ink)}
  :root{--pw-font:sans-serif;--pw-ink:#242424;--pw-copy:#555;--pw-muted:#888;--pw-canvas:#f7f7f6;--pw-inset:#f1f1ef;--pw-surface:#fff;--pw-strong:#222;--pw-on-strong:#fff}
  :root.dark{--pw-ink:#e8e8e8;--pw-copy:#bcbcbc;--pw-muted:#999;--pw-canvas:#181818;--pw-inset:#303030;--pw-surface:#242424;--pw-strong:#eee;--pw-on-strong:#111}
  button{font:inherit;border:0;display:flex;align-items:center;justify-content:center;cursor:pointer}button:disabled{opacity:.5;cursor:default}
  .pw-composer-region{width:100%;max-width:768px;margin:auto}.pw-composer-intent{display:flex;gap:0}.pw-press-front{display:flex;align-items:center;justify-content:center}svg{flex-shrink:0}
  ${borderless}\n${styles}</style></head><body><div class="pw-composer-region"><div class="pw-composer-host pw-squircle-composer"><section class="afa-composer">
  <div class="pw-composer-context-rail"><span class="pw-composer-context-icons">${icon('context')}</span><span class="pw-composer-context-usage" aria-label="上下文已使用 57%"><i class="pw-composer-context-ring" aria-hidden="true"></i><span>57%</span></span></div>
  <div class="afa-card pw-composer-card"><div class="pw-composer-row">
  <textarea aria-label="工作输入" placeholder="问点什么…">${state === 'filled' ? '帮我分析' : ''}</textarea>
  </div><div class="pw-composer-toolbar">
  <div class="pw-composer-leading"><div class="pw-composer-intent"><button aria-label="输入方式与选项">${icon('add')}</button><button aria-label="执行权限">${icon('permission')}</button></div></div>
  <div class="pw-composer-trailing">
  <div class="pw-composer-controls"><div class="pw-conversation-model-controls">${chip('model', '切换模型：测试模型', locked)}${chip('reasoning', '推理强度：高', locked)}</div></div>
  <div class="afa-toolbar"><button class="afa-iconbtn" aria-label="语音输入">${icon('mic')}</button>${state === 'filled' ? `<button class="pw-press" aria-label="发送"><span class="pw-press-front">${icon('send')}</span></button>` : ''}</div>
  </div></div></div></section></div></div><script>window.clicks=[];document.querySelectorAll('button').forEach(b=>b.addEventListener('click',()=>window.clicks.push(b.getAttribute('aria-label'))));</script></body></html>`;
}
try {
  for (const width of [320, 360, 390, 430, 768]) for (const theme of ['light', 'dark']) for (const state of ['empty', 'filled', 'disabled']) {
    const name = `${width}-${theme}-${state}`;
    const page = await browser.newPage({viewport: {width, height: 640}, reducedMotion: 'reduce'});
    try {
      await page.setContent(documentFor(theme, state));
      const model = page.locator('.pw-conversation-model-chip'), reasoning = page.locator('.pw-conversation-reasoning-chip');
      assert.equal(await model.locator('> span > svg:first-child').isVisible(), true, 'model icon was hidden with its BoardUI label wrapper');
      assert.equal(await reasoning.locator('> span > svg:first-child').isVisible(), true, 'reasoning icon was hidden with its BoardUI label wrapper');
      for (const control of [model, reasoning]) {
        assert.equal(await control.locator('> span > span').isVisible(), false, 'visual labels should stay icon-only');
        assert.equal(await control.locator('> span > svg:last-child').isVisible(), false, 'chevron should stay hidden');
      }
      const ring = await page.locator('.pw-composer-context-ring').evaluate(node => { const c=getComputedStyle(node); const r=node.getBoundingClientRect(); return {shadow:c.boxShadow, border:c.borderColor,width:r.width,height:r.height}; });
      assert.ok(ring.width >= 14 && ring.height >= 14 && ring.shadow.includes('inset'), 'borderless rules erased the hollow context ring: '+JSON.stringify(ring));
      assert.equal(await page.locator('.pw-composer-context-usage > span').isVisible(), true);
      const geometry = await page.evaluate(() => {const box=s=>document.querySelector(s).getBoundingClientRect();return {gray:box('.pw-composer-context-rail').width,white:box('.pw-composer-card').width,model:box('.pw-conversation-model-chip').x,reasoning:box('.pw-conversation-reasoning-chip').x,mic:box('.afa-iconbtn').x,overflow:document.documentElement.scrollWidth>innerWidth+1,editorBorder:getComputedStyle(document.querySelector('textarea')).borderWidth};});
      assert.ok(geometry.gray < geometry.white, 'context layer must remain shorter');
      assert.ok(geometry.model < geometry.reasoning && geometry.reasoning < geometry.mic, 'controls out of order');
      assert.equal(geometry.overflow, false, 'composer overflows viewport');
      assert.equal(geometry.editorBorder, '0px');
      const layout = await page.evaluate(() => {
        const editor = document.querySelector('textarea').getBoundingClientRect();
        const toolbar = document.querySelector('.pw-composer-toolbar').getBoundingClientRect();
        const targets = [...document.querySelectorAll('.pw-composer-toolbar button')].map(node => {
          const {x, y, width, height} = node.getBoundingClientRect();
          return {x, y, width, height};
        });
        return {editorWidth:editor.width, toolbarWidth:toolbar.width, editorBottom:editor.bottom, toolbarTop:toolbar.top, targets};
      });
      assert.ok(Math.abs(layout.editorWidth-layout.toolbarWidth)<1, 'editing must have the full toolbar width');
      assert.ok(layout.editorBottom<=layout.toolbarTop, 'editing must sit above the toolbar');
      assert.ok(layout.targets.every(target=>target.width>=44&&target.height>=44), 'every action needs a 44px target');
      assert.ok(layout.targets.every((target,index)=>index===0||layout.targets[index-1].x+layout.targets[index-1].width<=target.x+.5), 'toolbar targets must not overlap');
      if (state !== 'disabled') { await model.click(); await reasoning.click(); assert.deepEqual(await page.evaluate(()=>window.clicks), ['切换模型：测试模型','推理强度：高']); }
      if (width===390 && state==='empty') await page.screenshot({path:path.join(output, name+'.png')});
      checks.push({name,passed:true});
    } catch(error) {checks.push({name,passed:false,error:String(error.message)});}
    finally {await page.close();}
  }
} finally {await browser.close();}
const result={scope:'CSS/BoardUI DOM-contract regression only; not a full React/Android/backend acceptance test.',checks};
await fs.writeFile(path.join(output,'results.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({passed:checks.filter(c=>c.passed).length,total:checks.length,failures:checks.filter(c=>!c.passed)}));
if(checks.some(c=>!c.passed)) process.exitCode=1;
