"""An original local literal match, promoted by its owner to a private draft. No fallback to other sources."""
from desktop_owner_entry import _dialog
import windows_copy_find as lookup
import windows_owner_draft as draft


def start(ctx,settings,approved,word,hit,view,locked,observe):
    item,n,pos,_=hit
    if (locked() or not lookup.valid(word) or item not in approved.members or item.size>draft.MAX_TEXT
            or type(n) is not int or n<1 or type(pos) is not int or pos<0): return
    def prepare(check,_):
        def gate():
            check()
            if locked(): draft.block()
        gate()
        fresh=lookup.scope(lookup.catalog(ctx,approved.operation),approved.folder,[m.path for m in approved.members])
        if fresh!=approved: draft.block()
        # Read just this private copy file AND its historical Git blob through existing authority.
        base=draft.prepare(ctx,settings,approved.operation,item.path,gate)
        if (base.path!=item.path or base.commit!=approved.commit or base.algo!=approved.algo or base.oid!=item.oid
                or base.origin!=item.birth or len(base.raw)!=item.size or draft.digest(base.raw)!=item.sha): draft.block()
        _,_,text=draft.unpack(base.raw)
        if text!=view[0]: draft.block()
        parts=text.split("\r\n")
        if not 1<=n<=len(parts) or parts[n-1].find(word)!=pos: draft.block()
        prefix="\r\n".join(parts[:n-1])+("\r\n" if n>1 else "")+parts[n-1][:pos]
        at=len(prefix.encode("utf-16le"))//2
        if view[1]!=(at,at+len(word.encode("utf-16le"))//2): draft.block()
        gate(); return base,at
    visible,fixed=observe("核对这个命中的原底稿",item.path+f" · 第 {n} 行\n只重读这个原文件及其原 Git 对象，不枚举别的文件。",
                          locked,prepare,45)
    if not visible: return
    if fixed is None:
        _dialog("没有用这个命中起草","原 Git 文件、原副本、正文位置或可编辑格式不能共同核实。仍可回到原查找结果；没有写入。",[],retire=locked); return
    if not locked():
        from windows_owner_draft_entry import edit
        base,at=fixed
        edit(ctx,settings,approved.operation,base,locked,observe,start_at=at)
