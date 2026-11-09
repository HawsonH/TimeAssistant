package com.hawson.timeassistant.ui;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.*;
import android.speech.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.hawson.timeassistant.connections.AiCancellation;
import com.hawson.timeassistant.design.UiKit;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

final class VoiceInputController {
    private final AppCompatActivity activity;
    private final Supplier<EditText> input;
    private final AiToolsController tools;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> permission;
    private final ActivityResultLauncher<Intent> fallback;
    private MediaRecorder recorder;
    private SpeechRecognizer recognizer;
    private BottomSheetDialog dialog;
    private TextView status;
    private ProgressBar level;
    private File audio;
    private AiCancellation request;
    private VoiceDraft draft;
    private long started, generation;
    private boolean recording, transcribing, closed;
    private int peakAmplitude;
    private final Runnable recognitionTimeout = () -> { if (dialog != null && dialog.isShowing()) fail("识别超时，请重试"); };
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!recording || dialog == null || !dialog.isShowing()) return;
            long seconds = (SystemClock.elapsedRealtime() - started) / 1000;
            status.setText(String.format(Locale.ROOT, "正在录音  %02d:%02d", seconds / 60, seconds % 60));
            if (recorder != null) try {
                int amplitude = recorder.getMaxAmplitude(); peakAmplitude = Math.max(peakAmplitude, amplitude);
                level.setProgress(Math.min(100, amplitude * 100 / 32767));
            } catch (RuntimeException ignored) { }
            if (seconds >= 120) { finish(); return; }
            main.postDelayed(this, 150);
        }
    };
    VoiceInputController(AppCompatActivity activity, Supplier<EditText> input, AiToolsController tools) {
        this.activity = activity; this.input = input; this.tools = tools;
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startGranted(); else toast("未获得麦克风权限，可继续文字输入");
        });
        fallback = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            ArrayList<String> values = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (values != null && !values.isEmpty()) insert(values.get(0));
        });
        File[] stale = activity.getCacheDir().listFiles((dir, name) -> name.startsWith("voice-") && name.endsWith(".m4a"));
        if (stale != null) for (File file : stale) if (System.currentTimeMillis() - file.lastModified() > 3600000) file.delete();
    }
    void start() {
        if (recording || transcribing || closed) return;
        EditText field = input.get(); if (field == null) return;
        draft = new VoiceDraft(field.getText().toString(), field.getSelectionStart(), field.getSelectionEnd());
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return;
        }
        startGranted();
    }
    private void startGranted() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        if (input.get() == null || !input.get().isAttachedToWindow()) return;
        String engine = tools.services.get("speech.engine", "system");
        if (!"system".equals(engine) && !tools.services.hasSecret("aux:speech")) { tools.showSpeechSettings(); return; }
        show(); generation++; long session = generation; peakAmplitude = 0;
        if ("system".equals(engine)) {
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                dialog.dismiss();
                try { fallback.launch(recognitionIntent()); } catch (RuntimeException missing) {
                    new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity).setTitle("手机没有可用识别服务")
                            .setPositiveButton("配置语音 API", (d, w) -> tools.showSpeechSettings()).setNegativeButton("取消", null).show();
                }
                return;
            }
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(Bundle params) { }
                    public void onBeginningOfSpeech() { }
                    public void onRmsChanged(float rms) { if (level != null) level.setProgress(Math.max(0, Math.min(100, (int) (rms * 8)))); }
                    public void onBufferReceived(byte[] buffer) { }
                    public void onEndOfSpeech() { if (session != generation) return; recording = false; main.removeCallbacks(tick); status.setText("正在识别…"); main.postDelayed(recognitionTimeout, 15000); }
                    public void onError(int error) { if (session == generation) fail(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "未识别到语音，请重试" : "系统识别未完成，可重试或切换语音服务"); }
                    public void onResults(Bundle result) {
                        if (session != generation) return;
                        ArrayList<String> values = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (values == null || values.isEmpty()) { fail("未识别到文字，请重试"); return; }
                        dialog.dismiss(); insert(values.get(0));
                    }
                    public void onPartialResults(Bundle partial) { }
                    public void onEvent(int type, Bundle params) { }
                });
                recognizer.startListening(recognitionIntent());
            } catch (RuntimeException failed) { fail("无法启动系统识别，请检查语音服务"); return; }
        } else {
            try {
                audio = File.createTempFile("voice-", ".m4a", activity.getCacheDir());
                recorder = new MediaRecorder(); recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(16000); recorder.setAudioEncodingBitRate(64000); recorder.setOutputFile(audio.getAbsolutePath());
                recorder.prepare(); recorder.start();
            } catch (Exception failed) { fail("无法开始录音，请检查麦克风权限或占用"); return; }
        }
        recording = true; started = SystemClock.elapsedRealtime(); main.post(tick);
    }
    private Intent recognitionIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
    }
    private void show() {
        dialog = new BottomSheetDialog(activity);
        com.hawson.timeassistant.design.MotionSpec.configureDialog(dialog);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(UiKit.background(activity, UiKit.surface(activity), 0)); body.setClipToOutline(true);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density); body.setPadding(pad, pad, pad, pad);
        status = new TextView(activity); status.setTextSize(17); status.setText("准备录音"); body.addView(status);
        level = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); level.setMax(100); body.addView(level, new LinearLayout.LayoutParams(-1, pad));
        LinearLayout actions = new LinearLayout(activity); body.addView(actions);
        Button cancel = new com.google.android.material.button.MaterialButton(activity, null, android.R.attr.borderlessButtonStyle);
        cancel.setText("取消"); cancel.setMinHeight(pad * 2); actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1)); cancel.setOnClickListener(v -> dialog.dismiss());
        Button done = new com.google.android.material.button.MaterialButton(activity); done.setText("完成"); done.setMinHeight(pad * 2);
        actions.addView(done, new LinearLayout.LayoutParams(0, -2, 1)); done.setOnClickListener(v -> { if (recording) finish(); });
        body.addView(UiKit.row(activity, "语音设置", tools.speechLabel(), 0, () -> { dialog.dismiss(); tools.showSpeechSettings(); }));
        dialog.setContentView(body); dialog.setOnDismissListener(ignored -> cleanup()); dialog.show();
        dialog.getBehavior().setShouldRemoveExpandedCorners(false);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }
    private void finish() {
        if (!recording) return;
        recording = false; main.removeCallbacks(tick);
        if (recognizer != null) { status.setText("正在识别…"); recognizer.stopListening(); main.postDelayed(recognitionTimeout, 15000); return; }
        if (recorder == null) return;
        try { recorder.stop(); } catch (RuntimeException shortAudio) { fail("录音过短，请重试"); return; }
        recorder.release(); recorder = null;
        if (peakAmplitude < 64) { fail("未检测到声音，请靠近麦克风后重试"); return; }
        if (audio == null || audio.length() < 128) { fail("未录到有效音频，请重试"); return; }
        transcribing = true; status.setText("正在转写…"); level.setIndeterminate(true);
        request = new AiCancellation(); AiCancellation cancel = request; File file = audio; long session = generation;
        worker.execute(() -> {
            try {
                String result = tools.services.transcribe(file, cancel);
                main.post(() -> { if (session != generation || closed) return; dialog.dismiss(); insert(result); });
            } catch (Exception failure) {
                main.post(() -> { if (session == generation && !closed) fail(failure instanceof IllegalArgumentException ? failure.getMessage() : "转写未完成，请检查语音服务与网络后重试"); });
            } finally { file.delete(); }
        });
    }
    private void insert(String text) {
        EditText field = input.get(); if (field == null || draft == null || text.trim().isEmpty() || closed) return;
        if (draft.unchanged(field.getText().toString())) {
            field.setText(draft.insert(text)); field.setSelection(Math.min(field.length(), draft.start + text.length())); field.requestFocus();
        } else {
            new com.hawson.timeassistant.design.SoftAlertDialogBuilder(activity).setTitle("插入转写文字？").setMessage(text)
                .setNegativeButton("取消", null).setPositiveButton("插入", (d, w) -> {
                    EditText target = input.get(); if (target == null) return;
                    int at = Math.max(0, target.getSelectionStart()); target.getText().insert(at, text);
                }).show();
        }
    }
    private void fail(String message) { if (dialog != null) dialog.dismiss(); toast(message); }
    private void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    private void cleanup() {
        generation++; recording = false; transcribing = false; main.removeCallbacks(tick);
        main.removeCallbacks(recognitionTimeout);
        if (request != null) { request.cancel(); request = null; }
        if (recorder != null) { try { recorder.stop(); } catch (RuntimeException ignored) { } recorder.release(); recorder = null; }
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        if (audio != null) { audio.delete(); audio = null; }
    }
    void background() { if (dialog != null && dialog.isShowing()) dialog.dismiss(); }
    void close() { closed = true; background(); cleanup(); worker.shutdownNow(); }
}
