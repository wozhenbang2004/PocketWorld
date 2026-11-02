package dev.pocketengine.features.operations;

import android.app.Application;
import dev.pocketengine.features.assistant.OwnerComposerState;
import dev.pocketengine.features.assistant.OwnerComposerStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real native app-locales/legacy settings and original composer file; no task is submitted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},manifest=Config.NONE,application=Application.class)
public final class AppLanguageNativeTest {
    @Test public void languageSelectionPreservesOriginalConversationDraftVoiceAnchorAndPendingIdentity()throws Exception{
        Application app=RuntimeEnvironment.getApplication();AppLanguage.select(app,"system");
        OwnerComposerStore original=new OwnerComposerStore(app.getFilesDir());OwnerComposerState state=original.load().get(3,TimeUnit.SECONDS);
        String conversation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        OwnerComposerState.Draft draft=state.selectConversation(conversation);
        state.edit(draft.key,draft.revision,"主人原草稿 / keep exact user text","steer",new JSONArray());
        state.reading(draft.key,27,88,UUID.randomUUID().toString(),UUID.randomUUID().toString(),true);
        state.retainVoice(draft.key,UUID.randomUUID().toString(),"原听写 final text");
        state.prepare(UUID.randomUUID().toString(),draft.key,state.active().revision,"a".repeat(64),new JSONObject().put("taskId",task).put("runId",run).put("epoch",2));
        original.save(state).get(3,TimeUnit.SECONDS);String before=state.toJson().toString();
        try{
            AppLanguage.select(app,"en");assertEquals("en",AppLanguage.selected(app));
            assertEquals("en",AppLanguage.wrap(app).getResources().getConfiguration().getLocales().get(0).getLanguage());
            assertEquals("en-US",AppLanguage.speechLocale(app).toLanguageTag());
            OwnerComposerState restored=new OwnerComposerStore(app.getFilesDir()).load().get(3,TimeUnit.SECONDS);
            assertEquals(before,restored.toJson().toString());assertEquals(conversation,restored.active().conversationId);
            assertEquals(task,restored.pending().get(0).originalTarget().getString("taskId"));
            assertEquals(run,restored.pending().get(0).originalTarget().getString("runId"));
            AppLanguage.select(app,"zh-Hans");assertEquals("zh",AppLanguage.wrap(app).getResources().getConfiguration().getLocales().get(0).getLanguage());
            assertEquals("zh-CN",AppLanguage.speechLocale(app).toLanguageTag());
            assertEquals(before,new OwnerComposerStore(app.getFilesDir()).load().get(3,TimeUnit.SECONDS).toJson().toString());
        }finally{AppLanguage.select(app,"system");}
        assertEquals("system",AppLanguage.selected(app));
    }
}
