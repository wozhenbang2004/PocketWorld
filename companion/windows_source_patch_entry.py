"""Owner choice to retain or revisit one original, local file-change artifact."""
import ntpath
import time

from desktop_owner_entry import _dialog
from windows_source_diff import selected
from windows_source_patch import BANK,FILENAME,_key,_target,check,intent,records,save


def detail(ctx,op,row,locked,*,read_patch=None):
    if locked(): return
    f=row["fields"]
    try: state=check(ctx,op,row,locked)
    except Exception: state="unavailable"
    if locked(): return
    text=f"{f['path']}\n原提交 {f['commit'][:12]}；计划保存的补丁 {f['bytes']} 字节。"
    if f["replaced"]: text+="\n对照的一侧是当时由你另行选定的替代文件；没有将它追认为保存时的原件。"
    actions=[("ids","查看完整版本与字节摘要")]
    if state=="saved":
        text+="\n原成果目录、原文件身份和完整补丁 SHA-256 本次均相符。"
        actions.insert(0,("locate","在资源管理器定位 change.patch\n只选中这个本机产物；不会打开文件、应用补丁或发送。"))
    elif state=="pending": text+="\n只确认原保存意图，没有完整的落盘回执。可能已留有文件；不会按名称认领或自动再写。"
    else: text+="\n这次不能核实原补丁的最终记录、原文件身份和字节。不改从同名文件取回，也不重做。"
    if state=="saved" and read_patch is not None: actions.insert(0,("read-patch","在伴侣中阅读完整原补丁"))
    pick=_dialog("单文件变更的本机成果",text,actions,retire=locked)
    if locked(): return
    if pick=="read-patch" and read_patch is not None: read_patch(); return
    if pick=="locate":
        try: check(ctx,op,row,locked,show=True)
        except Exception:
            if not locked(): _dialog("没有打开资源管理器","原成果目录、原文件、唯一条目或系统定位未能同时核实。未改开上级目录或执行文件。",[],retire=locked)
    elif pick=="ids":
        _dialog("这些固定版本的编号",
                f"原提交：{f['commit']}\n原 Git blob：{f['originalOid']}\n选定字节计算的 blob 号（未提交）：{f['currentOid']}"
                f"\n原文件 SHA-256：{f['baseSha']}\n选定 SHA-256：{f['currentSha']}\n完整补丁 SHA-256：{f['patchSha']}",[],retire=locked)


def browse(ctx,op,locked,observe=None,*,draft_only=False,draft_settings=None):
    from windows_added_source import records as additions
    from windows_added_source_entry import detail as added_detail
    from windows_removed_source import records as removals
    from windows_removed_source_entry import detail as removed_detail
    from windows_owner_draft import records as drafts
    from windows_owner_draft_entry import detail as draft_detail
    from windows_new_source_draft import records as new_drafts
    from windows_new_source_draft_entry import detail as new_detail
    rows=[]; unavailable=False
    for reader,kind in (((drafts,"draft"),) if draft_only else ((records,"tracked"),(additions,"added"),(removals,"removed"),(drafts,"draft"),(new_drafts,"newdraft"))):
        try: rows.extend((kind,r) for r in reader(ctx,op).values())
        except Exception: unavailable=True  # A damaged/newer bank never hides valid results in the other one.
    rows.sort(key=lambda pair:pair[1]["at"],reverse=True)
    if not rows:
        if not locked():
            chosen=_dialog("没有可核实的单文件补丁","有一组原记录不可读取；不会在其他目录中寻找替代品。" if unavailable else
                           "还没有这份单文件副本的可核实草稿补丁。" if draft_only else "尚无可用单项；已经另存的多文件原件可单独查看。",
                           [("sets","查看这个副本的多文件补丁")] if observe else [],retire=locked)
            if chosen=="sets" and not locked():
                from windows_patch_set_entry import menu
                menu(ctx,op,locked,observe)
        return
    page=0
    while not locked():
        actions=[]
        for i,(kind,r) in enumerate(rows[page*4:page*4+4],page*4):
            f=r["fields"]; path=f["path"]; stamp=time.strftime("%m-%d %H:%M",time.localtime(r["at"]/1000))
            note=("机主拟新增 · "+f["draftOid"][:12] if kind=="newdraft" else "建议移除原路径 · "+f["originalOid"][:12] if kind=="removed" else
                  "机主草稿 · "+f["draftOid"][:12] if kind=="draft" else
                  "新增文件 · "+f["sourceOid"][:12] if kind=="added" else "修改原文件 · "+f["currentOid"][:12])
            actions.append((("patch",i),(path if len(path)<=100 else "…"+path[-99:])+f"\n{stamp} · "+note))
        if observe: actions.append(("sets","多文件交付补丁\n选择几份原产物另存，或查看以前的完整组合。"))
        if page: actions.append(("prev","上一页"))
        if 4*(page+1)<len(rows): actions.append(("next","下一页"))
        pick=_dialog("这次副本另存的单文件变更","包括只有保存意图的记录。打开后只核对已留存的原补丁。"+(" 有一组记录暂不能核实。" if unavailable else ""),actions,retire=locked)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        if pick=="sets" and observe and not locked():
            from windows_patch_set_entry import menu
            menu(ctx,op,locked,observe); return
        if pick is not None and not locked():
            kind,r=rows[pick[1]]
            if kind=="draft": draft_detail(ctx,op,r,locked,settings=draft_settings)
            else: {"tracked":detail,"added":added_detail,"removed":removed_detail,"newdraft":new_detail}[kind](ctx,op,r,locked)
        return


def prepare(ctx,settings,op,item,observed,locked,observe):
    visible,result=observe("准备完整的单文件补丁","重新核实刚才的原提交和选定版本；还没有保存文件。",locked,
                          lambda checkpoint,_:selected(ctx,settings,op,item,checkpoint,patch=True),30)
    if not visible: return
    if (not result or result.get("status")!="patch" or any(result.get(k)!=observed.get(k) for k in
            ("path","commit","baseBytes","baseSha","replaced","currentBytes","currentSha"))):
        _dialog("没有准备补丁","两个准确版本已变化、不可再读、超出文本范围或无完整差异。不会换用其他文件，也没有写入。",[],retire=locked); return
    try:
        fields=intent(ctx,op,item,result); rid=_key(fields)
        rows=records(ctx,op)
        prior=next((r for r in rows.values() if r["id"]==rid),None)
    except Exception:
        _dialog("没有准备保存","本机原记录暂不能核对，没有写入文件。",[],retire=locked); return
    if prior is not None: detail(ctx,op,prior,locked); return
    if len(rows)>=BANK:
        _dialog("这次副本已保留八份单文件变更记录","不会覆盖已有文件或清空记录。",[],retire=locked); return
    location=ntpath.join(_target(ctx,rid),FILENAME)
    body=(f"{fields['path']}\n原提交 {fields['commit'][:12]} → 选定文件 {fields['currentOid'][:12]}。"
          f"\n完整文本补丁 {fields['bytes']} 字节，包含此文件全部变更及邻近上下文，不是刚才的显示节选。"
          f"\n本机新位置：{location}\n保存的是刚核准的字节；不覆盖文件，不改写或应用到源仓库，也不发送给手机。")
    if _dialog("保存这一个文件的完整补丁？",body,[("save","确认保存到这个新位置")],retire=locked)!="save" or locked(): return
    visible,row=observe("保存本机变更产物","正在以不覆盖的方式保存并回读原补丁……",locked,
                        lambda checkpoint,_:save(ctx,settings,op,item,result,locked,checkpoint),30)
    if not visible: return
    if row is None:
        _dialog("没有取得完整保存回执","可回到这次副本的「已另存的单文件变更」查原记录。不能仅凭此次提示断言文件已写入或未写入；不会自动再存。",[],retire=locked)
    else: detail(ctx,op,row,locked)
