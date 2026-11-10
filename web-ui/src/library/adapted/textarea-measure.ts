// Measurement-only adaptation of react-textarea-autosize 8.5.9, commit
// ed1894cd8611d99fbea1c47adcf6ee522b1030fd (MIT, Copyright 2013 Andrey Popp).
// Exact getSizingData / calculateNodeHeight / forceHiddenStyles and license are
// retained in ../upstream/react-textarea-autosize. The real Affect editor stays
// mounted and keeps its caret, scroll position and event ownership.

const sizingProperties = [
  'borderBottomWidth', 'borderLeftWidth', 'borderRightWidth', 'borderTopWidth',
  'boxSizing', 'fontFamily', 'fontSize', 'fontStyle', 'fontWeight',
  'letterSpacing', 'lineHeight', 'paddingBottom', 'paddingLeft', 'paddingRight',
  'paddingTop', 'tabSize', 'textIndent', 'textRendering', 'textTransform',
  'width', 'wordBreak', 'wordSpacing', 'scrollbarGutter',
  // The approved composer wraps long unbroken text and inherits font features.
  'overflowWrap', 'whiteSpace', 'direction', 'fontFeatureSettings',
  'fontVariationSettings', 'fontKerning',
] as const;

const hiddenStyles = {
  'min-height': '0', 'max-height': 'none', height: '0', visibility: 'hidden',
  overflow: 'hidden', position: 'fixed', 'z-index': '-1000', top: '0', right: '0',
  display: 'block', 'pointer-events': 'none', margin: '0',
} as const;

/** A per-editor measuring node, never a second interactive editor. Unlike the
 * upstream module singleton, it retains no draft text between measurements
 * and is removed when its composer owner or real editor changes. */
export function createTextareaMeasure(editor: HTMLTextAreaElement) {
  const document = editor.ownerDocument;
  const probe = document.createElement('textarea');
  probe.tabIndex = -1;
  probe.setAttribute('aria-hidden', 'true');
  probe.inert = true;
  for (const [property, value] of Object.entries(hiddenStyles)) {
    probe.style.setProperty(property, value, 'important');
  }

  return {
    editor,
    measure() {
      if (!editor.isConnected) return null;
      const style = document.defaultView?.getComputedStyle(editor);
      if (!style?.boxSizing || !(parseFloat(style.width) > 0)) return null;
      // Snapshot all live geometry before writing the isolated measuring node.
      const sizing = sizingProperties.map(property => [property, style[property]] as const);
      const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0);
      const border = (parseFloat(style.borderTopWidth) || 0) + (parseFloat(style.borderBottomWidth) || 0);
      const borderBox = style.boxSizing === 'border-box';
      const lineHeight = parseFloat(style.lineHeight) || 24;
      const minimum = Math.max(48, lineHeight + (borderBox ? padding + border : 0), parseFloat(style.minHeight) || 0);
      const maximum = Math.max(minimum, parseFloat(style.maxHeight) || 144);
      const currentHeight = parseFloat(style.height) || minimum;

      for (const [property, value] of sizing) {
        if (probe.style[property] !== value) probe.style[property] = value;
      }
      probe.wrap = editor.wrap;
      if (!probe.isConnected) document.body.appendChild(probe);
      let height: number;
      try {
        probe.value = editor.value || 'x';
        // Chromium WebView: no Firefox-specific duplicate assignment/read.
        height = probe.scrollHeight + (borderBox ? border : -padding);
      } finally {
        probe.value = '';
      }
      return { target: Math.min(maximum, Math.max(minimum, height)), overflow: height > maximum, currentHeight };
    },
    destroy() {
      probe.value = '';
      probe.remove();
    },
  };
}
