package dev.pocketengine.features.local.language;

import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.*;
import dev.pocketengine.core.RunControl;
import java.util.*;
import org.json.JSONObject;

/** Explicit owner acceptance changes only the existing private draft cache. No automatic save,
 * source CAS, execution approval or project process follows a language edit. */
public final class ProjectLanguageDrafts {
    private ProjectLanguageDrafts(){}
    public static List<ProjectFileDraft> stage(LocalModule local,ProjectFileDraftStore store,ProjectLanguageSnapshot snapshot,JSONObject exactResult,ProjectFileDraft selectedDraft,RunControl original)throws Exception {
        original.check();local.requireLanguageSnapshotForOwner(snapshot);
        if(!snapshot.projectId.equals(selectedDraft.projectId)||!snapshot.checkoutId.equals(selectedDraft.checkoutId)||!snapshot.path.equals(selectedDraft.path)||snapshot.draftRevision!=selectedDraft.revision||!snapshot.draftSha256.equals(ProjectSources.sha256(ProjectLanguageSnapshot.encode(selectedDraft.text))))throw new SecurityException("语言修改不是这份原编辑稿");
        List<ProjectLanguageEdits.FileEdit> edits=ProjectLanguageEdits.review(snapshot,exactResult);List<ProjectFileDraft> before=new ArrayList<>(),after=new ArrayList<>();
        for(ProjectLanguageEdits.FileEdit edit:edits){
            original.check();ProjectFileDraft retained=store.open(snapshot.projectId,snapshot.checkoutId,edit.path,false);
            if(edit.path.equals(selectedDraft.path)&&retained!=selectedDraft)throw new IllegalStateException("语言预览后当前草稿已被更新，未覆盖");
            if(retained.dirty()&&!retained.text.equals(edit.beforeText))throw new IllegalStateException("另一个待修改文件有未分析的新草稿，请先处理："+edit.path);
            ProjectFileDraft observed;
            if(edit.path.equals(snapshot.path)&&!snapshot.originalFilePresent&&retained.creating&&"absent".equals(retained.baseHash))observed=retained;
            else {
                JSONObject review=local.handle("reviewFile",new JSONObject().put("checkoutId",snapshot.checkoutId).put("path",edit.path));
                if(!edit.path.equals(snapshot.path)&&!snapshot.expectedHash(edit.path).equals(review.getString("expectedHash")))throw new SecurityException("跨文件重命名目标已变化，请重新分析");observed=retained.observe(review);
            }
            if(observed.conflict())throw new IllegalStateException("待修改文件存在保存冲突，请先比较当前文件："+edit.path);
            if(!observed.text.equals(edit.beforeText))throw new IllegalStateException("原稿已不同于完整语义预览，未应用部分修改");
            before.add(retained);after.add(observed.edit(edit.afterText));
        }
        original.check();local.requireLanguageSnapshotForOwner(snapshot);original.check();store.retainAllExact(before,after);return Collections.unmodifiableList(after);
    }
}
