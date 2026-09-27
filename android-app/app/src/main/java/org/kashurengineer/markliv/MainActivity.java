package org.kashurengineer.markliv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final int CAMERA_REQ_CODE = 102;
    private static final String PREFS_NAME = "MarkLIVPrefs";
    private static final String KEY_GEMINI_API = "gemini_api_key";

    private WebView webView;
    private String apiKey = "";
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient;
    private final JSONArray conversationHistory = new JSONArray();

    private static final String SYSTEM_INSTRUCTION =
            "You are JARVIS, the legendary AI assistant for MARK LIV, custom engineered by Kashur Engineer (@kashurengineer). " +
            "You are witty, concise, highly capable, and speak naturally like Tony Stark's JARVIS. " +
            "You can open apps, search the web, analyze images, answer any question, and assist the user on Android.";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, this);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        apiKey = prefs.getString(KEY_GEMINI_API, "");

        checkPermissions();
        initSpeechRecognizer();
    }

    public class WebAppInterface {
        @JavascriptInterface
        public String getApiKey() {
            return apiKey;
        }

        @JavascriptInterface
        public void saveApiKey(String key) {
            apiKey = key.trim();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_GEMINI_API, apiKey).apply();
            speakOut("Gemini API initialized and ready, Sir.");
        }

        @JavascriptInterface
        public void toggleVoiceRecognition() {
            mainHandler.post(() -> {
                if (!isListening) {
                    startVoiceRecognition();
                } else {
                    stopVoiceRecognition();
                }
            });
        }

        @JavascriptInterface
        public void processTextCommand(String command) {
            mainHandler.post(() -> handleUserQuery(command, null));
        }

        @JavascriptInterface
        public void openCamera() {
            mainHandler.post(() -> {
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                    startActivityForResult(takePictureIntent, CAMERA_REQ_CODE);
                } else {
                    Toast.makeText(MainActivity.this, "Camera not available", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            tts.setPitch(0.95f);
            tts.setSpeechRate(1.05f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    mainHandler.post(() -> runJs("setAssistantState('SPEAKING', 0.8)"));
                }

                @Override
                public void onDone(String utteranceId) {
                    mainHandler.post(() -> runJs("setAssistantState('ONLINE', 0.1)"));
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.post(() -> runJs("setAssistantState('ONLINE', 0.1)"));
                }
            });
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.INTERNET,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS
            }, PERMISSION_REQ_CODE);
        }
    }

    private void initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    mainHandler.post(() -> runJs("setAssistantState('LISTENING', 0.5)"));
                }

                @Override
                public void onBeginningOfSpeech() {
                    mainHandler.post(() -> runJs("setAssistantState('LISTENING', 0.85)"));
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    float norm = Math.max(0.1f, Math.min(1.0f, (rmsdB + 2f) / 10f));
                    mainHandler.post(() -> runJs("audioLevel = " + norm + ";"));
                }

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    mainHandler.post(() -> {
                        isListening = false;
                        runJs("setAssistantState('PROCESSING', 0.2)");
                    });
                }

                @Override
                public void onError(int error) {
                    mainHandler.post(() -> {
                        isListening = false;
                        runJs("setAssistantState('ONLINE', 0.1)");
                    });
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String query = matches.get(0);
                        runJs("appendLog('You', " + JSONObject.quote(query) + ");");
                        handleUserQuery(query, null);
                    }
                    isListening = false;
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}
                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void startVoiceRecognition() {
        if (speechRecognizer != null) {
            isListening = true;
            runJs("setAssistantState('LISTENING', 0.6)");
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechRecognizer.startListening(intent);
        }
    }

    private void stopVoiceRecognition() {
        if (speechRecognizer != null && isListening) {
            speechRecognizer.stopListening();
            isListening = false;
            runJs("setAssistantState('ONLINE', 0.1)");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAMERA_REQ_CODE && resultCode == RESULT_OK && data != null) {
            Bundle extras = data.getExtras();
            if (extras != null) {
                Bitmap imageBitmap = (Bitmap) extras.get("data");
                if (imageBitmap != null) {
                    runJs("appendLog('You', '📷 [Captured Camera Photo for Vision Analysis]');");
                    handleUserQuery("Analyze what you see in this photo and describe it clearly.", imageBitmap);
                }
            }
        }
    }

    private void handleUserQuery(String query, @Nullable Bitmap image) {
        String lower = query.toLowerCase();

        // Native Quick App Launch Intents
        if (lower.contains("open youtube")) {
            speakOut("Opening YouTube.");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")));
            return;
        } else if (lower.contains("open whatsapp")) {
            speakOut("Opening WhatsApp.");
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
            if (launchIntent != null) startActivity(launchIntent);
            return;
        } else if (lower.contains("open spotify")) {
            speakOut("Opening Spotify.");
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
            if (launchIntent != null) startActivity(launchIntent);
            return;
        } else if (lower.contains("open chrome") || lower.contains("open browser")) {
            speakOut("Opening Chrome.");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")));
            return;
        }

        // Send to Gemini AI API
        queryGeminiAPI(query, image);
    }

    private void queryGeminiAPI(String prompt, @Nullable Bitmap image) {
        if (apiKey.isEmpty()) {
            runJs("appendLog('ERR', 'No Gemini API Key set. Tap ⚙️ settings to enter your key.'); openSettings();");
            return;
        }

        try {
            JSONObject content = new JSONObject();
            JSONArray parts = new JSONArray();

            if (image != null) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                image.compress(Bitmap.CompressFormat.JPEG, 85, stream);
                byte[] byteArray = stream.toByteArray();
                String base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Image);
                parts.put(new JSONObject().put("inline_data", inlineData));
            }

            parts.put(new JSONObject().put("text", prompt));
            content.put("role", "user");
            content.put("parts", parts);
            conversationHistory.put(content);

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("contents", conversationHistory);

            JSONObject systemInstructionObj = new JSONObject();
            JSONArray sysParts = new JSONArray();
            sysParts.put(new JSONObject().put("text", SYSTEM_INSTRUCTION));
            systemInstructionObj.put("parts", sysParts);
            bodyJson.put("system_instruction", systemInstructionObj);

            RequestBody body = RequestBody.create(
                    bodyJson.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            runJs("setAssistantState('THINKING', 0.3);");

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    mainHandler.post(() -> {
                        runJs("setAssistantState('ONLINE', 0.1); appendLog('ERR', " + JSONObject.quote(e.getMessage()) + ");");
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String resStr = response.body().string();
                            JSONObject json = new JSONObject(resStr);
                            JSONArray candidates = json.optJSONArray("candidates");
                            if (candidates != null && candidates.length() > 0) {
                                JSONObject candidate = candidates.getJSONObject(0);
                                JSONObject contentObj = candidate.getJSONObject("content");
                                JSONArray partsArr = contentObj.getJSONArray("parts");
                                String reply = partsArr.getJSONObject(0).getString("text").trim();

                                JSONObject modelTurn = new JSONObject();
                                modelTurn.put("role", "model");
                                JSONArray modelParts = new JSONArray();
                                modelParts.put(new JSONObject().put("text", reply));
                                modelTurn.put("parts", modelParts);
                                conversationHistory.put(modelTurn);

                                mainHandler.post(() -> {
                                    runJs("appendLog('JARVIS', " + JSONObject.quote(reply) + ");");
                                    speakOut(reply);
                                });
                                return;
                            }
                        } catch (Exception e) {
                            mainHandler.post(() -> runJs("appendLog('ERR', " + JSONObject.quote(e.getMessage()) + ");"));
                        }
                    } else {
                        mainHandler.post(() -> runJs("appendLog('ERR', 'API Error: Code " + response.code() + "'); setAssistantState('ONLINE', 0.1);"));
                    }
                }
            });

        } catch (Exception e) {
            runJs("appendLog('ERR', " + JSONObject.quote(e.getMessage()) + ");");
        }
    }

    private void speakOut(String text) {
        if (tts != null) {
            String cleanText = text.replaceAll("[*#_`]", "");
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId_" + System.currentTimeMillis());
        }
    }

    private void runJs(String script) {
        mainHandler.post(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        super.onDestroy();
    }
}
