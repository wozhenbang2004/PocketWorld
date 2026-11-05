// Transient controls sit above the page's existing navigation/detail stack.
// Registration is tied to their open lifecycle, never to DOM selectors or a
// synthetic Escape event (Android calls the bridge directly).
const overlays: Array<{ close: () => void }> = [];

export function registerOverlayBack(close: () => void): () => void {
  const entry = { close };
  overlays.push(entry);
  return () => {
    const index = overlays.indexOf(entry);
    if (index !== -1) overlays.splice(index, 1);
  };
}

export function consumeOverlayBack(): boolean {
  const top = overlays.at(-1);
  if (!top) return false;
  top.close();
  return true;
}
