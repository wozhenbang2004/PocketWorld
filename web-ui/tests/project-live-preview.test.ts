import test from 'node:test';
import assert from 'node:assert/strict';
import { projectLiveBounds } from '../src/project-live-preview.ts';

test('a live native page is clipped below the task header and above its footer', () => {
  assert.deepEqual(projectLiveBounds({ x: 12, y: 50, width: 336, height: 420 }, 360, 800, [{ x: 0, y: 120, width: 360, height: 300 }]), { visible: true, rect: { x: 12, y: 120, width: 336, height: 300 } });
});
test('overlays, off-screen pages and malformed bounds cannot leave a native view interactive', () => {
  assert.equal(projectLiveBounds({ x: 0, y: 0, width: 320, height: 400 }, 360, 800, [], false).visible, false);
  assert.equal(projectLiveBounds({ x: 0, y: 900, width: 320, height: 400 }, 360, 800).visible, false);
  assert.equal(projectLiveBounds({ x: NaN, y: 0, width: 320, height: 400 }, 360, 800).visible, false);
});
