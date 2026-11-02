package dz.sila.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * صِلة — غلاف أندرويد لتطبيق الويب (تطوير TitteriSoft).
 * يعرض index.html من assets، ويوفّر جسراً (AndroidBridge) لـ:
 *  - التعرّف على الكلام: مستمر عبر SpeechRecognizer (مع تفضيل خدمة Google)،
 *    أو عبر نافذة Google «تكلّم الآن» كطريقة بديلة تعمل على كل الهواتف تقريباً.
 *  - النطق (TextToSpeech)، إبقاء الشاشة مضاءة، حفظ النسخة الاحتياطية، واختيار الصور.
 */
public class MainActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_SAVE = 3;
    private static final int REQ_DIALOG = 4;
    private static final int REQ_MIC_WEB = 5;
    private PermissionRequest pendingWebPermission;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String GOOGLE_PKG = "com.google.android.googlequicksearchbox";

    private WebView web;
    private WebViewAssetLoader loader;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingLang = "ar-DZ";
    private String currentLang = null;
    private int langFallback = 0;
    private boolean listeningActive = false;   // جلسة استماع مستمر جارية
    private boolean resumeNeeded = false;          // 0: اللغة كما هي، 1: "ar"، 2: دون تحديد لغة
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0F766E);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // الاستماع بالذكاء الاصطناعي: الصفحة تطلب الميكروفون (getUserMedia)
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String r : request.getResources())
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    if (!audio) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingWebPermission != null) pendingWebPermission.deny();
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_WEB);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    /* ======================= الجسر ======================= */
    private class Bridge {
        @JavascriptInterface
        public void startListening(String lang) { runOnUiThread(() -> startRecognition(lang)); }

        @JavascriptInterface
        public void stopListening() { runOnUiThread(() -> { listeningActive = false; if (recognizer != null) recognizer.cancel(); }); }

        @JavascriptInterface
        public void startDialog(String lang) { runOnUiThread(() -> startDialogRecognition(lang)); }

        @JavascriptInterface
        public boolean isAvailable() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }

        @JavascriptInterface
        public String serviceInfo() {
            ComponentName cn = findRecognitionService();
            return cn == null ? "default" : cn.getPackageName();
        }

        @JavascriptInterface
        public void speak(String text, String lang) {
            if (!ttsReady) return;
            runOnUiThread(() -> {
                try { tts.setLanguage(Locale.forLanguageTag(lang)); } catch (Exception ignored) { }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sila");
            });
        }

        @JavascriptInterface
        public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String content) {
            runOnUiThread(() -> {
                pendingSave = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE); }
                catch (Exception e) { pendingSave = null; js("window.__backupSaved&&window.__backupSaved(false)"); }
            });
        }
    }

    /* ============ التعرّف المستمر (SpeechRecognizer) ============ */

    /** نفضّل خدمة Google لأنها الأفضل في العربية؛ بعض الهواتف تضع خدمة الشركة المصنّعة افتراضياً */
    private ComponentName findRecognitionService() {
        try {
            List<ResolveInfo> list = getPackageManager().queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            if (list == null || list.isEmpty()) return null;
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo != null && GOOGLE_PKG.equals(ri.serviceInfo.packageName))
                    return new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void startRecognition(String lang) {
        if (lang == null || lang.isEmpty()) lang = "ar-DZ";
        if (!lang.equals(currentLang)) { currentLang = lang; langFallback = 0; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("error", "service-not-allowed|0", false);
            emit("end", null, false);
            return;
        }
        try {
            if (recognizer == null) {
                ComponentName cn = findRecognitionService();
                recognizer = (cn != null)
                        ? SpeechRecognizer.createSpeechRecognizer(this, cn)
                        : SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            String useLang = langFallback == 0 ? lang : (langFallback == 1 ? lang.split("-")[0] : null);
            if (useLang != null) {
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, useLang);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, useLang);
            }
            recognizer.startListening(i);
            listeningActive = true;
        } catch (Exception e) {
            destroyRecognizer();
            emit("error", "client|-1", false);
            emit("end", null, false);
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { emit("ready", null, false); }
        @Override public void onBeginningOfSpeech() { emit("speech", null, false); }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle b) {
            String t = first(b);
            if (t != null && !t.isEmpty()) emit("result", t, false);
        }

        @Override
        public void onResults(Bundle b) {
            listeningActive = false;
            String t = first(b);
            if (t != null && !t.isEmpty()) emit("result", t, true);
            emit("end", null, false);
        }

        @Override
        public void onError(int error) {
            listeningActive = false;
            String code;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "not-allowed"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: code = "network"; break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: code = "no-speech"; break;
                case SpeechRecognizer.ERROR_AUDIO: code = "audio"; break;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
                case 13: // ERROR_LANGUAGE_UNAVAILABLE (API 31)
                    code = "language";
                    if (langFallback < 2) langFallback++;
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    code = "busy";
                    destroyRecognizer();     // نعيد إنشاء المُعرِّف في المحاولة التالية
                    break;
                default:
                    code = "server";
                    destroyRecognizer();
            }
            emit("error", code + "|" + error, false);
            emit("end", null, false);
        }
    };

    /* ============ الطريقة البديلة: نافذة Google «تكلّم الآن» ============ */
    private void startDialogRecognition(String lang) {
        if (lang == null || lang.isEmpty()) lang = "ar-DZ";
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "تكلّم الآن…");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ_DIALOG);
        } catch (Exception e) {
            emit("error", "service-not-allowed|-2", false);
            emit("end", null, false);
        }
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /** يرسل حدثاً إلى JavaScript: window.__srEvent(type, text, isFinal) */
    private void emit(String type, String text, boolean isFinal) {
        js("window.__srEvent&&window.__srEvent(" + JSONObject.quote(type) + ","
                + (text == null ? "null" : JSONObject.quote(text)) + "," + isFinal + ")");
    }

    /* ======================= النتائج ======================= */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC_WEB) {
            if (pendingWebPermission != null) {
                if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                    pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else pendingWebPermission.deny();
                pendingWebPermission = null;
            }
            return;
        }
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecognition(pendingLang);
        } else {
            emit("error", "not-allowed|9", false);
            emit("end", null, false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DIALOG) {
            ArrayList<String> list = (resultCode == RESULT_OK && data != null)
                    ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
            if (list != null && !list.isEmpty() && list.get(0) != null && !list.get(0).isEmpty()) {
                emit("result", list.get(0), true);
            } else {
                emit("error", "cancelled|0", false);
            }
            emit("end", null, false);
            return;
        }
        if (requestCode == REQ_SAVE) {
            boolean ok = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    out.write(pendingSave.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                } catch (Exception ignored) { }
            }
            pendingSave = null;
            js("window.__backupSaved&&window.__backupSaved(" + ok + ")");
            return;
        }
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listeningActive && recognizer != null) {
            recognizer.cancel();
            listeningActive = false;
            resumeNeeded = true;      // نستأنف عند العودة للتطبيق
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeNeeded) {
            resumeNeeded = false;
            emit("end", null, false);  // JavaScript يعيد التشغيل إن كان الاستماع ما زال مفعّلاً
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyRecognizer();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
