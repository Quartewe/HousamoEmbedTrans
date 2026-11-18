package com.quarty.housamoembedtrans;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.bridge.TranslationServiceClient;
import com.quarty.housamoembedtrans.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/** The main looper owns this choice visit, draft and window. Only HET owns the job. */
final class GameContinuationUi implements Application.ActivityLifecycleCallbacks {
    private enum Page { CLOSED, INPUT, WAIT, ERROR }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Supplier<TranslationServiceClient> client;
    // The IO executor orders submission and subsequent cancellation even if the user backs out immediately.
    private Page page = Page.CLOSED;
    private Activity activity;
    private Context dialogContext;
    private AlertDialog dialog;
    private TextInputEditText editor;
    private JSONObject node;
    private String draft = "";
    private String requestId, errorText;
    private int errorResource;

    GameContinuationUi(Supplier<TranslationServiceClient> client) { this.client = client; }
    void refreshConfig(Runnable read) { io.execute(read); }

    void prepare(String nodeJson) {
        io.execute(() -> {
            try {
                TranslationServiceClient service = client.get();
                JSONObject context = new JSONObject(nodeJson);
                context.remove("play_token");
                if (service != null) service.prepareContinuation(context);
            } catch (Exception e) {
                Log.game("Could not prepare story continuation: " + e.getMessage());
            }
        });
    }

    void open(String nodeJson) {
        main.post(() -> {
            try {
                JSONObject next = new JSONObject(nodeJson);
                if (node != null && node.getString("play_token").equals(next.getString("play_token"))) {
                    if (page != Page.CLOSED) return;
                } else closeVisit();
                node = next;
                page = Page.INPUT;
                render();
            } catch (Exception e) {
                fail(0, message(e));
            }
        });
    }

    void leave() { main.post(this::closeVisit); }

    void playbackError(String message) { main.post(() -> fail(0, message)); }

    /** Runs on the existing terminal delivery reader. ACK follows UI ownership/visit arbitration. */
    void accept(String id, JSONObject result) throws Exception {
        JSONObject error = result.optJSONObject("error");
        String record = error == null ? result.getString("record_id") : null;
        String steps = error == null ? result.getJSONArray("steps").toString() : null;
        FutureTask<Void> apply = new FutureTask<>(() -> {
            if (id.equals(requestId)) {
                if (error != null) fail(0, error.optString("message"));
                else play(record, steps);
            }
            return null;
        });
        if (Looper.myLooper() == main.getLooper()) apply.run();
        else if (!main.post(apply)) throw new IllegalStateException("Game looper stopped");
        apply.get();
    }

    private void closeVisit() {
        requestId = null;
        dismissWindow();
        node = null;
        draft = "";
        page = Page.CLOSED;
    }

    private void cancelFromWindow() {
        closeVisit();
    }

    private void submit() {
        if (page != Page.INPUT || editor == null) return;
        String line = editor.getText().toString();
        if (!validLine(line)) return;
        final String id = UUID.randomUUID().toString();
        JSONObject visit = node;
        requestId = id;
        page = Page.WAIT;
        render();
        io.execute(() -> {
            try {
                TranslationServiceClient service = client.get();
                JSONObject submission = new JSONObject(visit.toString());
                submission.remove("play_token");
                if (service != null && service.submitContinuation(id, submission, line)) return;
                postFailure(id, service == null ? R.string.continuation_offline :
                    R.string.continuation_admission_failed, null);
            } catch (android.os.RemoteException e) {
                // The remote may already have persisted this id when its reply was interrupted.
                postFailure(id, 0, message(e));
            } catch (Exception e) {
                postFailure(id, 0, message(e));
            }
        });
    }

    private void postFailure(String id, int res, String message) {
        main.post(() -> { if (id.equals(requestId)) fail(res, message); });
    }

    private void play(String recordId, String steps) {
        requestId = null;
        if (MainHook.nativePlayContinuation(node.optString("play_token"), recordId, steps)) closeVisit();
        else fail(R.string.continuation_playback_failed, null);
    }

    private void fail(int res, String text) {
        requestId = null;
        errorText = text;
        errorResource = res;
        page = Page.ERROR;
        render();
    }

    private void dismissWindow() {
        if (editor != null) {
            draft = editor.getText().toString();
            InputMethodManager ime = (InputMethodManager) editor.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            editor = null;
        }
        if (dialog != null) {
            dialog.setOnCancelListener(null);
            dialog.dismiss();
            dialog = null;
        }
    }

    private void render() {
        dismissWindow();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() ||
                page == Page.CLOSED) return;
        try {
            if (dialogContext == null) dialogContext = new GameDialogContext(activity);
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(dialogContext);
            Context ui = builder.getContext();
            if (page == Page.ERROR) {
                builder.setTitle(R.string.continuation_error)
                    .setMessage(errorResource == 0 ? errorText : ui.getText(errorResource))
                    .setPositiveButton(android.R.string.ok, (d, w) -> cancelFromWindow());
            } else if (page == Page.WAIT) {
                LinearLayout body = body(ui);
                body.addView(new ProgressBar(ui), -2, dp(ui, 30));
                TextView line = new TextView(ui);
                line.setText(draft);
                android.widget.ScrollView scroll = new android.widget.ScrollView(ui);
                scroll.addView(line);
                body.addView(scroll);
                builder.setTitle(R.string.continuation_wait).setView(body)
                    .setNegativeButton(R.string.continuation_cancel_generation, (d, w) -> cancelFromWindow());
            } else {
                LinearLayout body = body(ui);
                addInput(body, ui);
                builder.setTitle(R.string.continuation_title).setView(body)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> cancelFromWindow());
                if (page == Page.INPUT) builder.setPositiveButton(R.string.continuation_submit, null);
            }
            dialog = builder.create();
            dialog.setOwnerActivity(activity);
            dialog.setOnCancelListener(d -> { if (d == dialog) cancelFromWindow(); });
            dialog.setCanceledOnTouchOutside(false);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.show();
            if (page == Page.INPUT) {
                android.widget.Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setEnabled(validLine(draft));
                submit.setOnClickListener(v -> submit());
                editor.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        submit.setEnabled(validLine(s.toString()));
                    }
                    public void afterTextChanged(Editable s) {}
                });
            }
        } catch (Exception e) {
            Log.game("Story continuation dialog failed: " + message(e));
            Toast.makeText(activity, message(e), Toast.LENGTH_LONG).show();
            closeVisit();
        }
    }

    private static boolean validLine(String value) {
        return com.quarty.housamoembedtrans.continuation.ContinuationContent.hasText(value);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout body(Context c) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(c, 24), dp(c, 6), dp(c, 24), dp(c, 8));
        return body;
    }

    private void addInput(LinearLayout body, Context c) {
        TextInputLayout field = new TextInputLayout(c);
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setHint(R.string.continuation_input_hint);
        editor = new TextInputEditText(field.getContext());
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(2);
        editor.setMaxLines(4);
        editor.setText(draft);
        editor.setSelection(editor.length());
        field.addView(editor);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, 10);
        body.addView(field, p);
    }

    /** Game window token and input services, together with the module's resources and M3 theme. */
    private static final class GameDialogContext extends ContextWrapper {
        private final Resources resources;
        private final Resources.Theme theme;
        private LayoutInflater inflater;
        GameDialogContext(Activity host) throws Exception {
            super(host);
            Context module = host.createPackageContext(HetBridgeContract.MODULE_PACKAGE, 0)
                .createConfigurationContext(host.getResources().getConfiguration());
            resources = module.getResources();
            theme = resources.newTheme();
            theme.applyStyle(R.style.Theme_HET_GameContinuation, true);
        }
        @Override public Resources getResources() { return resources; }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public Resources.Theme getTheme() { return theme; }
        @Override public ClassLoader getClassLoader() { return GameContinuationUi.class.getClassLoader(); }
        @Override public Object getSystemService(String name) {
            if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
                if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                return inflater;
            }
            return super.getSystemService(name);
        }
    }

    public void onActivityResumed(Activity current) {
        // The game also hosts permission and external sign-in Activities. Only Unity owns story input.
        boolean unity = false;
        for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
            if ("com.unity3d.player.UnityPlayerActivity".equals(type.getName()) ||
                "com.unity3d.player.UnityPlayerGameActivity".equals(type.getName())) {
                unity = true;
                break;
            }
        }
        if (!unity) return;
        if (activity != current) dialogContext = null;
        activity = current;
        render();
    }
    public void onActivityPaused(Activity current) {
        if (activity == current) { dismissWindow(); dialogContext = null; activity = null; }
    }
    public void onActivityDestroyed(Activity current) {
        if (current == activity) { dismissWindow(); dialogContext = null; activity = null; }
    }
    public void onActivityCreated(Activity a, Bundle b) {}
    public void onActivityStarted(Activity a) {}
    public void onActivityStopped(Activity a) {}
    public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}
