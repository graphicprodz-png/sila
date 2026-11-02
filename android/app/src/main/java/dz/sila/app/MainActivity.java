package dz.sila.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * صِلة — غلاف أندرويد لتطبيق الويب.
 * يعرض index.html من مجلد assets، ويوفّر جسراً (AndroidBridge) للتعرّف على الكلام
 * عبر SpeechRecognizer الخاص بالهاتف، لأن WebView لا يدعم Web Speech API.
 */
public class MainActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final String HOST = "appassets.androidplatform.net";

    private WebView web;
    private WebViewAssetLoader loader;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingLang = "ar-DZ";

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
                // الروابط الخارجية (مثل صفحة المفاتيح) تُفتح في المتصفح
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));

        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    /** الواجهة التي يستدعيها JavaScript */
    private class Bridge {
        @JavascriptInterface
        public void startListening(String lang) {
            runOnUiThread(() -> startRecognition(lang));
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> { if (recognizer != null) recognizer.cancel(); });
        }

        @JavascriptInterface
        public boolean isAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void speak(String text, String lang) {
            if (!ttsReady) return;
            runOnUiThread(() -> {
                try { tts.setLanguage(Locale.forLanguageTag(lang)); } catch (Exception ignored) { }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sila");
            });
        }
    }

    private void startRecognition(String lang) {
        if (lang == null || lang.isEmpty()) lang = "ar-DZ";
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("error", "service-not-allowed", false);
            emit("end", null, false);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            emit("error", "aborted", false);
            emit("end", null, false);
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
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
            String t = first(b);
            if (t != null && !t.isEmpty()) emit("result", t, true);
            emit("end", null, false);
        }

        @Override
        public void onError(int error) {
            String code;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "not-allowed"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: code = "network"; break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: code = "no-speech"; break;
                default: code = "aborted";
            }
            emit("error", code, false);
            emit("end", null, false);
        }
    };

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /** يرسل حدثاً إلى JavaScript: window.__srEvent(type, text, isFinal) */
    private void emit(String type, String text, boolean isFinal) {
        final String js = "window.__srEvent&&window.__srEvent(" + JSONObject.quote(type) + ","
                + (text == null ? "null" : JSONObject.quote(text)) + "," + isFinal + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecognition(pendingLang);
        } else {
            emit("error", "not-allowed", false);
            emit("end", null, false);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
