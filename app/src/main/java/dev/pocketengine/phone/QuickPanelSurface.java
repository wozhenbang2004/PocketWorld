package dev.pocketengine.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.pocketengine.ui.UiAppearance;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;

/** One native, explicitly opened owner surface. It never observes or submits on its own. */
final class QuickPanelSurface implements AutoCloseable {
    interface Actions {
        void capture(boolean includeScreenshot);
        void cancelPreview();
        void send(String note);
        void queryReceipt();
        void noteChanged(String note);
        void clearDraft();
        void openOriginalTask();
        default void voice() { }
        default void reviewAudio() { }
        default void setAudio(String reviewId,boolean muted) { }
        void close();
    }

    private enum Phase { LOADING, BLOCKED, READY, CAPTURING, REVIEW, SENDING, OPENING_VOICE, UNKNOWN, SAVED, ERROR }
    private final Context context;
    private final WindowManager windows;
    private final Actions actions;
    private final String goal;
    private final String location;
    private final LinearLayout root;
    private final WindowManager.LayoutParams layout;
    private Phase phase = Phase.READY;
    private boolean attached;
    private boolean expanded;
    private boolean hidden;
    private boolean includeScreenshot;
    private boolean querying;
    private boolean draftWritable = true;
    private String note = "";
    private String message;
    private PreviewContent preview;
    private PreviewContent noteReviewedFor;
    private String reviewedNote = "";
    private Bitmap image;
    private EditText noteInput;
    private AlertDialog noteDialog;
    private String draftStatus = "草稿只保存在本机，不会自动发送。";
    private LinearLayout body, footer;
    private boolean audioSupported, audioBusy;
    private org.json.JSONObject audioState;
    private String audioReviewId, audioTarget, audioMessage;

    QuickPanelSurface(Context context, String goal, String location, Actions actions) {
        this.context = new android.view.ContextThemeWrapper(context, UiAppearance.theme(context));
        this.windows = context.getSystemService(WindowManager.class);
        this.goal = goal;
        this.location = location;
        this.actions = actions;
        root = new LinearLayout(this.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = availableHeight();
                int size = MeasureSpec.getSize(heightSpec);
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) limit = Math.min(limit, size);
                super.onMeasure(widthSpec, expanded ? MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST) : heightSpec);
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismissPreview(); return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> { view.requestLayout(); return insets; });
        root.setElevation(dp(6));
        root.setBackground(background(UiAppearance.surface(context), 18));
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.BOTTOM | Gravity.END;
        layout.x = dp(12); layout.y = dp(76);
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        layout.setTitle("Pocket World · 原任务快捷补充");
    }

    void show() {
        mainOnly();
        if (attached) return;
        render();
        windows.addView(root, layout); attached = true;
    }

    void loadingDraft() { mainOnly(); draftWritable = false; phase = Phase.LOADING; message = null; render(); }
    void restoreDraft(String text) { mainOnly(); draftWritable = true; note = text; noteReviewedFor = null; phase = Phase.READY; message = text.isEmpty() ? null : "已恢复这个原任务的未发送说明；请重新捕获并核对来源后再发送。"; render(); }
    void draftUnavailable(String detail) { mainOnly(); phase = Phase.BLOCKED; message = detail; expanded = true; render(); }
    void draftNotice(String detail) { mainOnly(); draftStatus = detail; TextView shown = root.findViewWithTag("quick_panel_draft_status"); if (shown != null) shown.setText(detail); if (noteDialog != null) { TextView editorStatus = noteDialog.findViewById(android.R.id.message); if (editorStatus != null) editorStatus.setText(detail); } }
    void draftFailure(String detail) { mainOnly(); draftWritable = false; draftNotice(detail); Button send = root.findViewWithTag("quick_panel_send"); if (send != null) send.setEnabled(false); Button capture = root.findViewWithTag("quick_panel_capture"); if (capture != null) capture.setEnabled(false); Button voice = root.findViewWithTag("quick_panel_voice"); if (voice != null) voice.setEnabled(false); }
    void draftStored() { mainOnly(); boolean blocked = !draftWritable; draftWritable = true; draftNotice("已保存这个原任务的本机草稿；收起或关闭后可继续编辑。"); if (blocked && noteDialog == null) render(); }
    void draftCleared() { mainOnly(); note = ""; noteReviewedFor = null; draftStatus = "本机未发送说明已清除；没有撤回任何已登记输入。"; render(); }
    void capturing() { mainOnly(); clearPreview(); phase = Phase.CAPTURING; message = null; expanded = true; render(); }
    void preview(PreviewContent content) { mainOnly(); if (preview != content) clearPreview(); preview = content; phase = Phase.REVIEW; message = null; expanded = true; render(); }
    void sending() { mainOnly(); phase = Phase.SENDING; message = null; render(); }
    void openingVoice() { mainOnly(); phase = Phase.OPENING_VOICE; message = null; expanded = true; render(); }
    void unknown(String detail) { mainOnly(); querying = false; phase = Phase.UNKNOWN; message = detail; render(); }
    void querying() { mainOnly(); querying = true; render(); }
    void saved(String detail) { mainOnly(); querying = false; clearPreview(); note = ""; phase = Phase.SAVED; message = detail; render(); }
    void error(String detail) { mainOnly(); phase = Phase.ERROR; message = detail; expanded = true; render(); }
    void ready() { mainOnly(); clearPreview(); phase = Phase.READY; message = null; render(); }
    void audioScope(boolean supported) { audioSupported=supported; }
    void audioLoading(boolean write) { mainOnly();audioBusy=true;audioReviewId=null;audioMessage=write?"正在核对原 App 并请求这次声音修改…":"正在读取原后台目标 App 和实际声音状态…";render(); }
    void audioFailure(String detail) { mainOnly();audioBusy=false;audioReviewId=null;audioState=null;audioTarget=null;audioMessage=detail;render(); }
    void audioResult(org.json.JSONObject result,boolean review) {
        mainOnly();audioBusy=false;audioReviewId=review?result.optString("reviewId",null):null;
        audioTarget=result.optString("packageName")+" · user "+result.optInt("userId",-1);
        audioState=result.optBoolean("stateVerified")?result.optJSONObject("state"):null;
        audioMessage=review?"只作用于这个后台 App 的此资料；确认前还会重新核对原任务和目标。":!result.optBoolean("confirmed")?"结果尚未证实。请读取当前状态核对；不会重放这次操作。":switch(result.optString("outcome")){
            case "silenced_verified" -> "已读回目标 App 的静音状态。";
            case "owner_mode_preserved" -> "已保留主人后来设置的声音状态，并清理原恢复记录。";
            default -> "已核对并恢复原记录中的声音状态。";
        };
        render();
    }

    void captureHidden(boolean value) {
        mainOnly(); hidden = value; if (value) closeNoteEditor();
        root.setVisibility(value ? View.INVISIBLE : View.VISIBLE);
        updateLayout();
    }

    boolean visible() { return attached && !hidden; }
    View view() { return root; }

    private void render() {
        ((android.view.ContextThemeWrapper) context).setTheme(UiAppearance.theme(context));
        root.setBackground(background(UiAppearance.surface(context), 18));
        closeNoteEditor(); root.removeAllViews(); body = null; footer = null;
        if (image != null) { image.recycle(); image = null; }
        root.setPadding(dp(expanded ? 12 : 4), dp(expanded ? 8 : 4), dp(expanded ? 12 : 4), dp(expanded ? 8 : 4));
        if (!expanded) {
            String label = phase == Phase.UNKNOWN ? "待核对 · 原任务" : phase == Phase.SENDING ? "登记中 · 原任务" : phase == Phase.SAVED ? "已登记 · 原任务" : "补充原任务";
            Button open = button(label, () -> { expanded = true; render(); }, false);
            open.setContentDescription(phase == Phase.READY ? "打开原任务快捷补充；尚未读取页面" : label + "；打开只查看原状态，不重新捕获或发送");
            root.addView(open);
        } else {
            LinearLayout heading = row(); heading.setTag("quick_panel_header");
            TextView title = text("补充原任务", 16, UiAppearance.foreground(context)); title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setAccessibilityHeading(true);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(button("收起", () -> { expanded = false; render(); }, false));
            heading.addView(button("关闭", actions::close, false)); root.addView(heading);
            if (preview != null && preview.truncated && (phase == Phase.REVIEW || phase == Phase.ERROR)) {
                TextView partial = text("部分内容 · 本次捕获有截断", 12, UiAppearance.warning(context)); partial.setTag("quick_panel_partial"); root.addView(partial);
            }
            // Header and phase actions stay outside this one bounded scrolling body, including under IME/font scaling.
            ScrollView scroll = new ScrollView(context); scroll.setTag("quick_panel_body_scroll"); scroll.setFillViewport(false);
            body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(2), 0, dp(2), dp(6));
            scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
            footer = new LinearLayout(context); footer.setTag("quick_panel_phase_actions"); footer.setOrientation(LinearLayout.VERTICAL); root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
            TextView target = text(goal, 14, UiAppearance.foreground(context)); target.setMaxLines(3); target.setEllipsize(android.text.TextUtils.TruncateAt.END); body.addView(target);
            body.addView(text("原任务执行位置 · " + location, 12, UiAppearance.secondary(context)));
            renderAudioControls();
            if (phase == Phase.LOADING || phase == Phase.BLOCKED) {
                body.addView(text(phase == Phase.LOADING ? "正在读取这个原任务的本机草稿…" : message, 13, UiAppearance.secondary(context)));
                footer.addView(button("查看原任务", actions::openOriginalTask, false));
            } else if (phase == Phase.CAPTURING || phase == Phase.SENDING || phase == Phase.OPENING_VOICE) {
                ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
                progress.setIndeterminate(true); body.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
                body.addView(text(phase == Phase.CAPTURING ? "正在读取你刚才明确选择的手机主屏…" : phase == Phase.OPENING_VOICE ? "正在保留原说明并打开前台听写窗口。尚未录音或发送…" : "正在登记这份固定内容，未自动继续任务…", 13, UiAppearance.secondary(context)));
                if (phase == Phase.CAPTURING) footer.addView(button("取消这次捕获", this::dismissPreview, false));
                else footer.addView(button("查看原任务", actions::openOriginalTask, false));
            } else if (phase == Phase.UNKNOWN) {
                body.addView(text(message, 13, UiAppearance.warning(context)));
                body.addView(text("仅查询原发送身份，不再发送或捕获。关闭后仍可在原任务核对回执。", 12, UiAppearance.secondary(context)));
                Button query = button(querying ? "查询中…" : "查询原回执", actions::queryReceipt, true); query.setEnabled(!querying); footer.addView(query);
                footer.addView(button("查看原任务", actions::openOriginalTask, false));
            } else if (phase == Phase.SAVED) {
                body.addView(text(message, 14, UiAppearance.foreground(context)));
                footer.addView(button("查看原任务", actions::openOriginalTask, true));
                footer.addView(button("再补充一份页面", () -> { phase = Phase.READY; render(); }, false));
            } else if (preview != null) {
                renderPreview();
            } else {
                if (message != null) body.addView(text(message, 13, UiAppearance.warning(context)));
                body.addView(text("先切到需要的 App。仅在你点下方按钮时读取手机主屏，不会持续监看。", 13, UiAppearance.secondary(context)));
                renderDraftActions();
                CheckBox screenshot = new CheckBox(context); screenshot.setText("这次同时捕获截图"); screenshot.setTextSize(13);
                screenshot.setMinHeight(dp(48)); screenshot.setChecked(includeScreenshot); screenshot.setOnCheckedChangeListener((v, checked) -> includeScreenshot = checked); body.addView(screenshot);
                Button capture = button("捕获并预览", () -> actions.capture(includeScreenshot), true); capture.setTag("quick_panel_capture"); capture.setEnabled(draftWritable&&!audioBusy); footer.addView(capture);
                footer.addView(button("查看原任务", actions::openOriginalTask, false));
            }
        }
        updateLayout();
    }

    private void renderPreview() {
        PreviewContent content = preview;
        TextView sourceLabel = text("来源 · 当前手机主屏（捕获时） · " + content.appLabel, 13, UiAppearance.foreground(context)); sourceLabel.setTypeface(null, android.graphics.Typeface.BOLD); body.addView(sourceLabel);
        body.addView(text("只发送这份固定内容；之后切换 App 不会替换它。", 12, UiAppearance.secondary(context)));
        if (content.imageBytes != null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(content.imageBytes, 0, content.imageBytes.length, bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0 && (long) bounds.outWidth * bounds.outHeight <= 16_000_000) {
                image = BitmapFactory.decodeByteArray(content.imageBytes, 0, content.imageBytes.length);
                if (image != null) { ImageView picture = new ImageView(context); picture.setImageBitmap(image); picture.setScaleType(ImageView.ScaleType.FIT_CENTER); picture.setContentDescription("这次明确捕获的手机主屏截图"); body.addView(picture, new LinearLayout.LayoutParams(-1, dp(190))); }
            }
            if (image == null) body.addView(text("截图暂不能预览，请取消后重新捕获。", 13, UiAppearance.warning(context)));
        }
        if (!content.screenshotMessage.isBlank()) body.addView(text(content.screenshotMessage, 12, UiAppearance.secondary(context)));
        TextView source = text(content.text.isBlank() ? "这次没有可读取的页面文字。" : content.text, 14, UiAppearance.foreground(context));
        source.setTextIsSelectable(true); source.setTag("quick_panel_source"); body.addView(source);
        if (message != null) body.addView(text(message, 13, UiAppearance.warning(context)));
        body.addView(text("只登记到原任务，不更换执行位置或原有审批。", 12, UiAppearance.secondary(context)));
        if (!note.isEmpty() && !noteReviewed()) body.addView(text("已保留未发送说明。请打开编辑器核对它是否适用于这份新来源，再点“保存说明”；不会把旧说明自动带入另一份捕获。", 12, UiAppearance.warning(context)));
        Button edit = button(note.isEmpty() ? "添加说明（可选）" : "编辑说明（" + note.length() + "字）", this::editNote, false);
        edit.setTag("quick_panel_edit_note"); footer.addView(edit);
        renderVoiceAction();
        renderDraftStatus();
        if (!note.isEmpty()) footer.addView(button("清除未发送说明", actions::clearDraft, false));
        LinearLayout actionsRow = row();
        actionsRow.addView(button("取消这份内容", this::dismissPreview, false), new LinearLayout.LayoutParams(0, -2, 1));
        Button send = button("发送到原任务", () -> {
            if (note.length() > 1500) { message = "补充说明最多1500字，请编辑后再发送。"; render(); return; }
            if (!note.isEmpty() && !noteReviewed()) { message = "请先核对这份捕获对应的说明；尚未发送。"; render(); return; }
            actions.send(note);
        }, true);
        send.setEnabled(!audioBusy && canSendNote() && (!content.text.isBlank() || image != null) && (content.imageBytes == null || image != null));
        send.setTag("quick_panel_send"); actionsRow.addView(send, new LinearLayout.LayoutParams(0, -2, 1)); footer.addView(actionsRow);
    }

    private void editNote() {
        mainOnly();
        if (!attached || hidden || phase != Phase.READY && phase != Phase.REVIEW && phase != Phase.ERROR) return;
        closeNoteEditor(); final PreviewContent expected = preview;
        EditText editor = new EditText(context); editor.setText(note); editor.setTextSize(14); editor.setMinLines(2); editor.setMaxLines(4);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setHint("原任务的未发送说明"); editor.setContentDescription("原任务本机草稿；编辑、保存和关闭都不发送"); editor.setTag("quick_panel_note");
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(4), dp(16), dp(8));
        content.addView(text(expected == null ? "原任务说明；发送前仍需重新捕获并核对页面来源。" : "来源 · " + expected.appLabel + "。核对说明与这份捕获一致，再点“保存说明”；不会发送或重新捕获。", 12, UiAppearance.secondary(context))); content.addView(editor);
        TextView status = text(draftStatus, 12, UiAppearance.secondary(context)); status.setId(android.R.id.message); content.addView(status);
        ScrollView scroll = new ScrollView(context); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("补充说明").setView(scroll).setPositiveButton("保存说明", null).setNegativeButton("收起，保留草稿", null).create();
        noteDialog = dialog; noteInput = editor;
        final android.text.TextWatcher watcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable value) {
                if (noteInput != editor || noteDialog != dialog) return;
                note = value.toString(); noteReviewedFor = null; draftNotice("正在保存本机草稿…"); actions.noteChanged(note);
                if (note.length() > 1500) editor.setError("发送说明最多1500字；草稿仍保留，请缩短后发送。");
            }
        };
        editor.addTextChangedListener(watcher);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.setOnDismissListener(ignored -> { editor.removeTextChangedListener(watcher); clearNoteEditor(dialog, editor); });
        try {
            dialog.show(); dialog.getWindow().setLayout(Math.max(1, Math.min(dp(380), context.getResources().getDisplayMetrics().widthPixels - dp(24))), -2);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!attached || hidden || preview != expected || phase != Phase.READY && phase != Phase.REVIEW && phase != Phase.ERROR) { closeNoteEditor(); return; }
                String text = editor.getText().toString(); if (text.length() > 1500) { editor.setError("补充说明最多1500字"); return; }
                note = text; noteReviewedFor = expected; reviewedNote = text; closeNoteEditor(); render();
            });
            editor.requestFocus(); editor.post(() -> {
                if (noteDialog == dialog && dialog.isShowing()) { InputMethodManager ime = context.getSystemService(InputMethodManager.class); if (ime != null) ime.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT); }
            });
        } catch (RuntimeException unavailable) {
            closeNoteEditor(); message = "说明编辑器暂不能打开；原捕获仍保留，未发送。"; render();
        }
    }
    private void clearNoteEditor(AlertDialog expected, EditText editor) {
        InputMethodManager ime = context.getSystemService(InputMethodManager.class);
        if (ime != null && editor.getWindowToken() != null) ime.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        editor.setText(""); editor.clearFocus();
        Button send = root.findViewWithTag("quick_panel_send"); if (send != null) send.setEnabled(canSendNote() && preview != null && (!preview.text.isBlank() || image != null) && (preview.imageBytes == null || image != null));
        if (noteInput == editor) noteInput = null;
        if (noteDialog == expected) noteDialog = null;
    }

    private void renderDraftActions() {
        Button edit = button(note.isEmpty() ? "添加未发送说明" : "编辑未发送说明（" + note.length() + "字）", this::editNote, false);
        edit.setTag("quick_panel_edit_note"); footer.addView(edit); renderDraftStatus();
        renderVoiceAction();
        if (!note.isEmpty()) footer.addView(button("清除未发送说明", actions::clearDraft, false));
    }
    private void renderDraftStatus() {
        TextView status = text(draftStatus, 12, UiAppearance.secondary(context)); status.setTag("quick_panel_draft_status"); body.addView(status);
    }
    private void renderVoiceAction() {
        Button voice = button("语音补充说明", actions::voice, false);
        voice.setTag("quick_panel_voice");voice.setEnabled(draftWritable&&!audioBusy);footer.addView(voice);
        body.addView(text("听写在应用前台进行；最终文字核对后追加为本机草稿，仍由你发送。",12,UiAppearance.secondary(context)));
    }
    private void renderAudioControls() {
        if(!audioSupported){body.addView(text("按 App 声音控制仅支持可核对的后台目标；当前执行位置不提供此操作。",12,UiAppearance.secondary(context)));return;}
        body.addView(text("目标 App 声音",13,UiAppearance.foreground(context)));
        if(audioTarget!=null)body.addView(text(audioTarget,12,UiAppearance.secondary(context)));
        if(audioState!=null){String mode=audioState.optString("mode");String label=switch(mode){case "ignore" -> "静音（已读回）";case "allow" -> "允许播放（已读回）";case "deny" -> "系统拒绝播放（已读回）";case "foreground" -> "仅前台播放（已读回）";default -> "系统默认（已读回）";};body.addView(text(label,12,UiAppearance.foreground(context)));}
        body.addView(text(audioMessage==null?"读取当前目标后，可只静音这个 App，或明确将它设为原记录的声音模式。当前支持 user 0；不会调整手机总音量。":audioMessage,12,UiAppearance.secondary(context)));
        boolean ready=!audioBusy&&phase!=Phase.CAPTURING&&phase!=Phase.SENDING&&phase!=Phase.OPENING_VOICE;
        if(audioReviewId!=null&&audioState!=null){
            final String review=audioReviewId;
            if(audioState.optBoolean("canMute")){Button mute=button("仅静音这个 App",()->actions.setAudio(review,true),false);mute.setTag("quick_panel_audio_mute");mute.setEnabled(ready);body.addView(mute);}
            if(audioState.optBoolean("canRestore")){
                body.addView(text("这是现在明确发出的声音设置；同时发生的其他修改可能以本次设置为准。",12,UiAppearance.secondary(context)));
                Button restore=button("按原记录设置声音",()->actions.setAudio(review,false),false);restore.setTag("quick_panel_audio_restore");restore.setEnabled(ready);body.addView(restore);
            }
            if(!audioState.optBoolean("canMute")&&!audioState.optBoolean("canRestore"))body.addView(text("当前没有本任务可恢复的声音记录，保持 App 现有状态。",12,UiAppearance.secondary(context)));
        }
        Button read=button(audioBusy?"核对声音中…":"读取目标 App 声音",actions::reviewAudio,false);read.setTag("quick_panel_audio_read");read.setEnabled(ready);body.addView(read);
    }
    private void closeNoteEditor() {
        AlertDialog current = noteDialog; EditText editor = noteInput; noteDialog = null; noteInput = null;
        if (editor != null) clearNoteEditor(current, editor);
        if (current != null) current.dismiss();
    }

    private void dismissPreview() {
        if (phase == Phase.SENDING || phase == Phase.UNKNOWN || phase == Phase.OPENING_VOICE) return;
        actions.cancelPreview();
    }

    private void updateLayout() {
        int displayWidth = context.getResources().getDisplayMetrics().widthPixels;
        layout.width = expanded ? Math.max(1, Math.min(dp(380), displayWidth - dp(24))) : -2;
        layout.y = dp(expanded ? 12 : 76);
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (expanded ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                | (hidden ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        if (attached) windows.updateViewLayout(root, layout);
    }

    private int availableHeight() {
        int height = context.getResources().getDisplayMetrics().heightPixels;
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            android.graphics.Insets blocked = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            height -= blocked.top + blocked.bottom;
        }
        // This only sizes a native surface; it never observes another app or changes original operation state.
        return Math.max(1, height - dp(expanded ? 36 : 88));
    }

    private boolean noteReviewed() { return preview != null && noteReviewedFor == preview && reviewedNote.equals(note); }
    private boolean canSendNote() { return draftWritable && !audioBusy && note.length() <= 1500 && (note.isEmpty() || noteReviewed()); }
    private void clearPreview() { if (preview != null) preview.close(); preview = null; noteReviewedFor = null; reviewedNote = ""; }
    private LinearLayout row() { LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(4), 0, dp(4)); return view; }
    private Button button(String label, Runnable action, boolean primary) {
        Button button = new Button(context); button.setText(label); button.setTextSize(13); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTextColor(primary ? UiAppearance.onPrimary(context) : UiAppearance.foreground(context)); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? UiAppearance.primary(context) : UiAppearance.surfaceVariant(context)));
        button.setOnClickListener(v -> action.run()); return button;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), UiAppearance.outline(context)); return drawable; }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static void mainOnly() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("QuickPanel UI requires the main thread"); }

    @Override public void close() {
        mainOnly(); closeNoteEditor(); clearPreview();
        if (image != null) { image.recycle(); image = null; }
        if (attached) { windows.removeView(root); attached = false; }
        if (noteInput != null) noteInput.setText("");
        TextView source = root.findViewWithTag("quick_panel_source"); if (source != null) source.setText("");
        root.removeAllViews(); body = null; footer = null; noteInput = null; note = "";
    }

    static final class PreviewContent implements AutoCloseable {
        final String appLabel, text, screenshotMessage;
        final boolean truncated;
        final byte[] imageBytes;
        PreviewContent(String appLabel, String text, boolean truncated, byte[] imageBytes, String screenshotMessage) {
            this.appLabel = appLabel; this.text = text; this.truncated = truncated;
            this.imageBytes = imageBytes == null ? null : imageBytes.clone(); this.screenshotMessage = screenshotMessage;
        }
        @Override public void close() { if (imageBytes != null) Arrays.fill(imageBytes, (byte) 0); }
    }
}
