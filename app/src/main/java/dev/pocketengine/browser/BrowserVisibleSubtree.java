package dev.pocketengine.browser;

/** Shared composed-tree visibility. A box-less layout container is not a hidden subtree. */
public final class BrowserVisibleSubtree {
    private BrowserVisibleSubtree() { }
    public static final String SCRIPT="""
const composedParent=e=>e.assignedSlot||e.parentElement||(e.getRootNode&&e.getRootNode().host)||null;
const visibleSubtree=e=>{let p=e,depth=0;while(p&&depth++<128){if(p.hidden||p.hasAttribute('hidden')||p.getAttribute('aria-hidden')==='true')return false;const c=getComputedStyle(p);if(c.display==='none'||c.visibility==='hidden'||c.visibility==='collapse'||Number(c.opacity)===0)return false;p=composedParent(p)}if(p){visibilityIncomplete();return false}return true};
const composedChildren=e=>{if(e.shadowRoot)return e.shadowRoot.childNodes;if(e.tagName==='SLOT'&&typeof e.assignedNodes==='function'){const a=e.assignedNodes({flatten:false});if(a.length)return a}return e.childNodes};
""";
}
