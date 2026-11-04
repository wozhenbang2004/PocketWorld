/*
 * Copyright 2025 AionUi (aionui.com), SPDX-License-Identifier: Apache-2.0.
 * Adapted from htmlInspectScript.ts at 11cd2a9a20d1e033157636efc570b41384c66d85.
 * PocketWorld changes: native-coordinate read only; no listeners, console bridge,
 * clipboard, overlay or toast. Bounded Unicode data and actual rectangle only.
 * See licenses/aionui-preview/{LICENSE,NOTICE.txt} in this application's assets.
 */
(function readPreviewElement(nativeX, nativeY, nativeWidth, nativeHeight) {
  'use strict';
  if (![nativeX, nativeY, nativeWidth, nativeHeight].every(Number.isFinite) || nativeWidth <= 0 || nativeHeight <= 0 || nativeX < 0 || nativeY < 0 || nativeX >= nativeWidth || nativeY >= nativeHeight) return null;
  const viewport = window.visualViewport;
  const width = viewport ? viewport.width : innerWidth, height = viewport ? viewport.height : innerHeight;
  const x = nativeX / nativeWidth * width + (viewport ? viewport.offsetLeft : 0);
  const y = nativeY / nativeHeight * height + (viewport ? viewport.offsetTop : 0);
  const element = document.elementFromPoint(x, y);
  if (!element || element.ownerDocument !== document || !element.isConnected) return null;
  const rect = element.getBoundingClientRect();
  const bounds = { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
  if (!Object.values(bounds).every(value => Number.isFinite(value) && Math.abs(value) <= 10000000) || rect.width < 0 || rect.height < 0) return null;
  function bounded(value, maximum) {
    let text = '', truncated = false;
    for (let index = 0; index < value.length;) {
      let part = value[index++], code = part.charCodeAt(0);
      if (code >= 0xd800 && code <= 0xdbff) {
        const next = value.charCodeAt(index);
        if (next >= 0xdc00 && next <= 0xdfff) part += value[index++];
        else { part = '\ufffd'; truncated = true; }
      } else if (code === 0 || code >= 0xdc00 && code <= 0xdfff) { part = '\ufffd'; truncated = true; }
      if (text.length + part.length > maximum) { truncated = true; break; }
      text += part;
      if (text.length === maximum && index < value.length) { truncated = true; break; }
    }
    return { text, truncated };
  }
  const tag = element.tagName.toLowerCase(), id = element.id ? '#' + element.id : '';
  const className = typeof element.className === 'string' ? element.className.split(/\s+/).filter(Boolean)[0] : '';
  const hint = bounded(tag + id + (className ? '.' + className : ''), 240);
  const text = bounded(element.textContent || '', 1200), html = bounded(element.outerHTML || '', 4000);
  return { selectorHint: hint.text, text: text.text, htmlSnippet: html.text, bounds,
    truncated: { selectorHint: hint.truncated, text: text.truncated, htmlSnippet: html.truncated } };
})
