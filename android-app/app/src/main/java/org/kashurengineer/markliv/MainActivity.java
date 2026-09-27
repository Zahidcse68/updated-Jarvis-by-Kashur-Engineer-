package org.kashurengineer.markliv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
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

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final int CAMERA_REQ_CODE = 102;
    private static final String PREFS_NAME = "MarkLIVPrefs";
    private static final String KEY_GEMINI_API = "gemini_api_key";
    private static final String KEY_VOICE_NAME = "gemini_voice_name";

    private WebView webView;
    private String apiKey = "";
    private String voiceName = "Aoede"; // Default Gemini Neural Voice (Aoede / Puck / Fenrir / Kore)
    private SpeechRecognizer speechRecognizer;
    private boolean isMuted = false;
    private boolean isSpeaking = false;
    private boolean isListening = false;
    private boolean isTorchOn = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient;
    private final JSONArray conversationHistory = new JSONArray();

    private AudioTrack activeAudioTrack;

    private static final String SYSTEM_INSTRUCTION =
            "You are JARVIS, Tony Stark's legendary AI assistant for MARK LIV, custom engineered by Kashur Engineer (@kashurengineer). " +
            "You speak concisely, intelligently, and with classic Tony Stark / JARVIS charisma. " +
            "Keep answers concise (1-2 sentences maximum) for natural fast speech. " +
            "You have full Android system control: flashlight, volume, phone dialing, camera vision, and app launching.";

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
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        apiKey = prefs.getString(KEY_GEMINI_API, "");
        voiceName = prefs.getString(KEY_VOICE_NAME, "Aoede");

        checkPermissions();
        initSpeechRecognizer();
        startTelemetryLoop();

        mainHandler.postDelayed(() -> {
            if (!isMuted) {
                startContinuousListening();
            }
        }, 1200);
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
            speakGeminiVoice("Gemini neural link established. All systems online, Sir.");
        }

        @JavascriptInterface
        public String getVoiceName() {
            return voiceName;
        }

        @JavascriptInterface
        public void saveVoiceName(String name) {
            voiceName = name.trim();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_VOICE_NAME, voiceName).apply();
            speakGeminiVoice("Voice profile switched to " + voiceName + ", Sir.");
        }

        @JavascriptInterface
        public void toggleMute() {
            mainHandler.post(() -> {
                isMuted = !isMuted;
                if (isMuted) {
                    stopListening();
                    runJs("setMuteState(true); setAssistantState('MUTED', 0.0);");
                    Toast.makeText(MainActivity.this, "JARVIS Microphone Muted", Toast.LENGTH_SHORT).show();
                } else {
                    runJs("setMuteState(false);");
                    Toast.makeText(MainActivity.this, "JARVIS Live Listening Active", Toast.LENGTH_SHORT).show();
                    startContinuousListening();
                }
            });
        }

        @JavascriptInterface
        public void triggerMicTap() {
            mainHandler.post(() -> {
                if (isMuted) {
                    isMuted = false;
                    runJs("setMuteState(false);");
                }
                startContinuousListening();
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

        @JavascriptInterface
        public void toggleTorch() {
            mainHandler.post(() -> setTorchMode(!isTorchOn));
        }

        @JavascriptInterface
        public void volumeUp() {
            mainHandler.post(() -> adjustVolume(AudioManager.ADJUST_RAISE));
        }

        @JavascriptInterface
        public void volumeDown() {
            mainHandler.post(() -> adjustVolume(AudioManager.ADJUST_LOWER));
        }

        @JavascriptInterface
        public void openAppSettings() {
            mainHandler.post(() -> startActivity(new Intent(Settings.ACTION_SETTINGS)));
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initSpeechRecognizer();
                if (!isMuted) {
                    mainHandler.postDelayed(this::startContinuousListening, 500);
                }
            }
        }
    }

    private void initSpeechRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    mainHandler.post(() -> runJs("setAssistantState('LISTENING', 0.4)"));
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
                    isListening = false;
                    mainHandler.post(() -> runJs("setAssistantState('THINKING', 0.25)"));
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    mainHandler.post(() -> {
                        if (!isMuted && !isSpeaking) {
                            mainHandler.postDelayed(MainActivity.this::startContinuousListening, 400);
                        } else {
                            runJs("setAssistantState('ONLINE', 0.1)");
                        }
                    });
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String query = matches.get(0);
                        runJs("appendLog('You', " + JSONObject.quote(query) + ");");
                        handleUserQuery(query, null);
                    } else {
                        if (!isMuted && !isSpeaking) {
                            startContinuousListening();
                        }
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}
                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void startContinuousListening() {
        if (isMuted || isSpeaking) return;
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.cancel();
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    speechRecognizer.startListening(intent);
                }
            } catch (Exception ignored) {}
        });
    }

    private void stopListening() {
        isListening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
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

    // ── SYSTEM COMMAND ROUTER ──
    private void handleUserQuery(String query, @Nullable Bitmap image) {
        String lower = query.toLowerCase().trim();

        // 1. Mute / Unmute Command
        if (lower.equals("mute") || lower.equals("stop listening") || lower.equals("be quiet") || lower.equals("go to sleep")) {
            isMuted = true;
            stopListening();
            runJs("setMuteState(true); setAssistantState('MUTED', 0.0); appendLog('JARVIS', 'Muted. Tap UNMUTE when you need me, Sir.');");
            speakGeminiVoice("Microphone muted, Sir.");
            return;
        }

        // 2. Flashlight / Torch Control
        if (lower.contains("turn on torch") || lower.contains("torch on") || lower.contains("turn on flashlight") || lower.contains("flashlight on") || lower.equals("light on")) {
            setTorchMode(true);
            String resp = "Flashlight activated, Sir.";
            runJs("appendLog('JARVIS', '🔦 " + resp + "');");
            speakGeminiVoice(resp);
            return;
        } else if (lower.contains("turn off torch") || lower.contains("torch off") || lower.contains("turn off flashlight") || lower.contains("flashlight off") || lower.equals("light off")) {
            setTorchMode(false);
            String resp = "Flashlight deactivated, Sir.";
            runJs("appendLog('JARVIS', '🔦 " + resp + "');");
            speakGeminiVoice(resp);
            return;
        }

        // 3. Volume Controls
        if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("raise volume")) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            String resp = "Volume increased, Sir.";
            runJs("appendLog('JARVIS', '🔊 " + resp + "');");
            speakGeminiVoice(resp);
            return;
        } else if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("lower volume")) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            String resp = "Volume decreased, Sir.";
            runJs("appendLog('JARVIS', '🔉 " + resp + "');");
            speakGeminiVoice(resp);
            return;
        }

        // 4. Phone Dialer
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            String target = query.substring(lower.startsWith("call ") ? 5 : 5).trim();
            dialPhoneNumber(target);
            String resp = "Opening phone dialer for " + target + ", Sir.";
            runJs("appendLog('JARVIS', '📞 " + resp + "');");
            speakGeminiVoice(resp);
            return;
        }

        // 5. App Launchers
        if (lower.contains("open youtube")) {
            launchAppOrUrl("com.google.android.youtube", "https://www.youtube.com");
            speakGeminiVoice("Opening YouTube.");
            return;
        } else if (lower.contains("open whatsapp")) {
            launchApp("com.whatsapp");
            speakGeminiVoice("Opening WhatsApp.");
            return;
        } else if (lower.contains("open spotify")) {
            launchAppOrUrl("com.spotify.music", "https://open.spotify.com");
            speakGeminiVoice("Opening Spotify.");
            return;
        } else if (lower.contains("open chrome") || lower.contains("open browser") || lower.contains("search google")) {
            launchAppOrUrl("com.android.chrome", "https://www.google.com");
            speakGeminiVoice("Opening Chrome.");
            return;
        } else if (lower.contains("open calculator")) {
            launchApp("com.google.android.calculator");
            speakGeminiVoice("Opening Calculator.");
            return;
        } else if (lower.contains("open settings")) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            speakGeminiVoice("Accessing device settings.");
            return;
        } else if (lower.contains("battery status") || lower.contains("battery percentage") || lower.equals("battery")) {
            int batt = getBatteryPercentage();
            String res = "Battery power is at " + batt + "%, Sir. Power cells nominal.";
            runJs("appendLog('JARVIS', " + JSONObject.quote("🔋 " + res) + ");");
            speakGeminiVoice(res);
            return;
        }

        // 6. Send to Gemini AI Engine
        queryGeminiAPI(query, image);
    }

    private void setTorchMode(boolean enable) {
        try {
            CameraManager cam = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cam != null) {
                String[] ids = cam.getCameraIdList();
                if (ids.length > 0) {
                    cam.setTorchMode(ids[0], enable);
                    isTorchOn = enable;
                    runJs("setTorchButtonState(" + enable + ");");
                }
            }
        } catch (Exception e) {
            runJs("appendLog('ERR', 'Flashlight control: ' + " + JSONObject.quote(e.getMessage()) + ");");
        }
    }

    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void dialPhoneNumber(String phone) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void launchApp(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) startActivity(intent);
        else Toast.makeText(this, "App not installed: " + packageName, Toast.LENGTH_SHORT).show();
    }

    private void launchAppOrUrl(String packageName, String fallbackUrl) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) startActivity(intent);
        else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)));
    }

    private int getBatteryPercentage() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) return (int) ((level / (float) scale) * 100);
        }
        return 100;
    }

    private void startTelemetryLoop() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int batt = getBatteryPercentage();
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    if (am != null) {
                        am.getMemoryInfo(mi);
                        double totalGB = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
                        double usedGB = (mi.totalMem - mi.availMem) / (1024.0 * 1024.0 * 1024.0);
                        runJs("updateTelemetry(" + batt + ", " + String.format(Locale.US, "%.1f", usedGB) + ", " + String.format(Locale.US, "%.1f", totalGB) + ");");
                    }
                } catch (Exception ignored) {}
                mainHandler.postDelayed(this, 3000);
            }
        }, 1500);
    }

    // ── GEMINI AI TEXT INFERENCE ──
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

            runJs("setAssistantState('THINKING', 0.3);");
            sendWithModelFallback(body, new String[]{
                "gemini-flash-lite-latest",
                "gemini-3.1-flash-lite-preview",
                "gemini-2.5-flash",
                "gemini-flash-latest"
            }, 0);

        } catch (Exception e) {
            runJs("appendLog('ERR', " + JSONObject.quote(e.getMessage()) + ");");
        }
    }

    private void sendWithModelFallback(RequestBody body, String[] models, int modelIndex) {
        if (modelIndex >= models.length) {
            mainHandler.post(() -> {
                runJs("appendLog('ERR', 'All AI models temporarily busy. Retrying in a moment.'); setAssistantState('ONLINE', 0.1);");
                if (!isMuted) startContinuousListening();
            });
            return;
        }

        String modelName = models[modelIndex];
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey;

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                sendWithModelFallback(body, models, modelIndex + 1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String resStr = response.body().string();
                        JSONObject json = new JSONObject(resStr);
                        JSONArray candidates = json.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            JSONObject candidate = candidates.getJSONObject(0);
                            JSONObject contentObj = candidate.getJSONObject("content");
                            JSONArray partsArr = contentObj.getJSONArray("parts");
                            StringBuilder replyBuilder = new StringBuilder();

                            for (int i = 0; i < partsArr.length(); i++) {
                                JSONObject p = partsArr.getJSONObject(i);
                                if (p.has("text")) {
                                    replyBuilder.append(p.getString("text"));
                                }
                            }
                            String reply = replyBuilder.toString().trim();

                            JSONObject modelTurn = new JSONObject();
                            modelTurn.put("role", "model");
                            JSONArray modelParts = new JSONArray();
                            modelParts.put(new JSONObject().put("text", reply));
                            modelTurn.put("parts", modelParts);
                            conversationHistory.put(modelTurn);

                            mainHandler.post(() -> {
                                runJs("appendLog('JARVIS', " + JSONObject.quote(reply) + ");");
                                speakGeminiVoice(reply);
                            });
                            return;
                        }
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> runJs("appendLog('ERR', " + JSONObject.quote(e.getMessage()) + ");"));
                    return;
                } finally {
                    response.close();
                }

                // If error, fallback to next model
                sendWithModelFallback(body, models, modelIndex + 1);
            }
        });
    }

    // ── DIRECT GEMINI NEURAL VOICE SYNTHESIS (AOEDE / PUCK / FENRIR / KORE) ──
    private void speakGeminiVoice(String rawText) {
        String cleanText = rawText.replaceAll("[*#_`]", "").trim();
        if (cleanText.isEmpty()) return;

        isSpeaking = true;
        stopListening();

        new Thread(() -> {
            try {
                // Call Gemini official Neural TTS model
                String ttsUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=" + apiKey;

                JSONObject ttsBody = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", cleanText));
                content.put("parts", parts);
                contents.put(content);
                ttsBody.put("contents", contents);

                JSONObject genConfig = new JSONObject();
                JSONArray respModalities = new JSONArray();
                respModalities.put("AUDIO");
                genConfig.put("responseModalities", respModalities);

                JSONObject speechCfg = new JSONObject();
                JSONObject voiceCfg = new JSONObject();
                JSONObject prebuilt = new JSONObject();
                prebuilt.put("voiceName", voiceName.isEmpty() ? "Aoede" : voiceName);
                voiceCfg.put("prebuiltVoiceConfig", prebuilt);
                speechCfg.put("voiceConfig", voiceCfg);
                genConfig.put("speechConfig", speechCfg);

                ttsBody.put("generationConfig", genConfig);

                RequestBody requestBody = RequestBody.create(
                        ttsBody.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(ttsUrl)
                        .post(requestBody)
                        .build();

                Response resp = httpClient.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    String jsonStr = resp.body().string();
                    JSONObject resJson = new JSONObject(jsonStr);
                    JSONArray candidates = resJson.optJSONArray("candidates");
                    if (candidates != null && candidates.length() > 0) {
                        JSONArray resParts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
                        for (int i = 0; i < resParts.length(); i++) {
                            JSONObject p = resParts.getJSONObject(i);
                            if (p.has("inlineData")) {
                                String b64Audio = p.getJSONObject("inlineData").getString("data");
                                byte[] pcmAudio = Base64.decode(b64Audio, Base64.DEFAULT);
                                playRawPcmAudio(pcmAudio, 24000);
                                resp.close();
                                return;
                            }
                        }
                    }
                }
                resp.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Fallback audio playback simulation
            mainHandler.post(() -> {
                isSpeaking = false;
                runJs("setAssistantState('ONLINE', 0.1);");
                if (!isMuted) startContinuousListening();
            });
        }).start();
    }

    private void playRawPcmAudio(byte[] pcmData, int sampleRate) {
        new Thread(() -> {
            try {
                int bufferSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(bufferSize, pcmData.length))
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                track.write(pcmData, 0, pcmData.length);
                mainHandler.post(() -> runJs("setAssistantState('SPEAKING', 0.85);"));
                track.play();

                long durationMs = (long) ((pcmData.length / 2.0 / sampleRate) * 1000);
                long startTime = System.currentTimeMillis();

                while (System.currentTimeMillis() - startTime < durationMs) {
                    float simLevel = 0.35f + (float) (Math.random() * 0.6);
                    mainHandler.post(() -> runJs("audioLevel = " + simLevel + ";"));
                    Thread.sleep(80);
                }

                track.stop();
                track.release();

                mainHandler.post(() -> {
                    isSpeaking = false;
                    runJs("setAssistantState('ONLINE', 0.1);");
                    if (!isMuted) {
                        mainHandler.postDelayed(MainActivity.this::startContinuousListening, 300);
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    isSpeaking = false;
                    runJs("setAssistantState('ONLINE', 0.1);");
                    if (!isMuted) startContinuousListening();
                });
            }
        }).start();
    }

    private void runJs(String script) {
        mainHandler.post(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (activeAudioTrack != null) {
            activeAudioTrack.release();
        }
        super.onDestroy();
    }
}
