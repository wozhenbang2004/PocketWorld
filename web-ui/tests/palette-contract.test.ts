import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = new URL('../src/', import.meta.url);
const read = (name) => readFileSync(new URL(name, source), 'utf8');
const root = (css, selector) => {
  const start = css.indexOf(selector + ' {');
  assert.ok(start >= 0, 'missing ' + selector);
  return css.slice(start, css.indexOf('}', start) + 1);
};
const token = (css, name) => {
  const match = css.match(new RegExp('--' + name + ':\\s*(#[0-9A-Fa-f]{6})\\s*;'));
  assert.ok(match, 'missing HEX token ' + name);
  return match[1];
};
const linear = (hex) => {
  const rgb = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255);
  const l = rgb.map(x => x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4);
  return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
};
const ratio = (a, b) => {
  const levels = [linear(a), linear(b)].sort((x, y) => y - x);
  return (levels[0] + 0.05) / (levels[1] + 0.05);
};
const light = root(read('product.css'), ':root');
const dark = root(read('presentation-theme.css'), ':root.dark');

test('text, button, warning and error contrast in both themes', () => {
  for (const block of [light, dark]) {
    for (const fg of ['pw-ink', 'pw-copy', 'pw-muted']) {
      assert.ok(ratio(token(block, fg), token(block, 'pw-surface')) >= 4.5, fg + ' contrast');
    }
    for (const [fg, bg] of [
      ['pw-on-strong', 'pw-strong'],
      ['pw-danger', 'pw-danger-surface'],
      ['pw-warning', 'pw-warning-surface']
    ]) {
      assert.ok(ratio(token(block, fg), token(block, bg)) >= 4.5, fg + ' contrast');
    }
  }
});

test('surfaces, focus and border have explicit distinct roles', () => {
  for (const block of [light, dark]) {
    assert.notEqual(token(block, 'pw-canvas'), token(block, 'pw-surface'));
    assert.notEqual(token(block, 'pw-surface'), token(block, 'pw-inset'));
    assert.notEqual(token(block, 'pw-inset'), token(block, 'pw-selected'));
    assert.ok(ratio(token(block, 'pw-focus'), token(block, 'pw-surface')) >= 3);
    assert.match(token(block, 'pw-edge'), /^#[0-9A-Fa-f]{6}$/);
  }
});

test('workspace inherits the root palette without light-in-dark overrides', () => {
  const surfaces = read('uiux-surfaces.css');
  const local = root(surfaces, '.pw-redesign');
  const localDark = root(surfaces, ':root.dark .pw-redesign');
  for (const name of ['pw-canvas', 'pw-surface', 'pw-inset', 'pw-selected']) {
    assert.ok(!local.includes('--' + name + ':'), 'local light ' + name);
    assert.ok(!localDark.includes('--' + name + ':'), 'local dark ' + name);
  }
  assert.ok(!read('presentation-theme.css').includes('--pw-edge: transparent'));
});

test('semantic tokens feed retained components and high contrast mode', () => {
  const theme = read('product.css');
  for (const item of ['--color-text-error-primary: var(--pw-danger)',
    '--cb-warn: var(--pw-warning)', '--cb-red: var(--pw-danger)',
    '--color-border-focus-ring: var(--pw-focus)']) {
    assert.ok(theme.includes(item), 'missing alias ' + item);
  }
  assert.ok(read('main.tsx').includes("import './color-roles.css';"));
  assert.ok(read('color-roles.css').includes('prefers-contrast: more'));
});

test('Android and Web share primary text and accent values', () => {
  const native = readFileSync(new URL('../../app/src/main/java/dev/pocketengine/ui/UiAppearance.java', source), 'utf8').toLowerCase();
  for (const [lite, dark] of [
    ['#181818', '#f4f4f4'], ['#555555', '#c4c4c4'], ['#242424', '#f3f3f3']
  ]) {
    assert.ok(native.includes(lite.replace('#', '0xff')));
    assert.ok(native.includes(dark.replace('#', '0xff')));
  }
});
