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
import android.media.AudioManager;
import android.media.MediaPlayer;
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
import android.speech.tts.TextToSpeech;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
    private static final String KEY_VOICE_NAME = "gemini_voice_name";
    private static final String KEY_AUTO_LISTEN = "auto_listen_continuous";

    private WebView webView;
    private String apiKey = "";
    private String voiceName = "en-gb"; // British Jarvis neural voice default
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private boolean isMuted = false;
    private boolean isSpeaking = false;
    private boolean isListening = false;
    private boolean isTorchOn = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient;
    private final JSONArray conversationHistory = new JSONArray();

    private MediaPlayer mediaPlayer;

    private static final String SYSTEM_INSTRUCTION =
            "You are JARVIS, Tony Stark's legendary cybernetic AI assistant for MARK LIV, custom engineered by Kashur Engineer (@kashurengineer). " +
            "You speak concisely, intelligently, and with classic witty British JARVIS demeanor. " +
            "Keep responses punchy, direct, and under 2-3 sentences for natural conversation. " +
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
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, this);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        apiKey = prefs.getString(KEY_GEMINI_API, "");
        voiceName = prefs.getString(KEY_VOICE_NAME, "en-gb");

        checkPermissions();
        initSpeechRecognizer();
        startTelemetryLoop();

        // Start hands-free dialogue mode after HUD initializes
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
            speakNeuralResponse("Gemini neural link established, Sir. Ready for your command.");
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
            speakNeuralResponse("Voice synthesis profile updated, Sir.");
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

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.UK);
            tts.setPitch(0.88f);
            tts.setSpeechRate(1.05f);
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
                            // Automatically restart continuous listening
                            mainHandler.postDelayed(MainActivity.this::startContinuousListening, 500);
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

    // ── CONTINUOUS HANDS-FREE LISTENING ──
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
            speakNeuralResponse("Microphone muted, Sir.");
            return;
        }

        // 2. Flashlight / Torch Control
        if (lower.contains("turn on torch") || lower.contains("torch on") || lower.contains("turn on flashlight") || lower.contains("flashlight on") || lower.equals("light on")) {
            setTorchMode(true);
            String resp = "Flashlight activated, Sir.";
            runJs("appendLog('JARVIS', '🔦 " + resp + "');");
            speakNeuralResponse(resp);
            return;
        } else if (lower.contains("turn off torch") || lower.contains("torch off") || lower.contains("turn off flashlight") || lower.contains("flashlight off") || lower.equals("light off")) {
            setTorchMode(false);
            String resp = "Flashlight deactivated, Sir.";
            runJs("appendLog('JARVIS', '🔦 " + resp + "');");
            speakNeuralResponse(resp);
            return;
        }

        // 3. Volume Controls
        if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("raise volume")) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            String resp = "Volume increased, Sir.";
            runJs("appendLog('JARVIS', '🔊 " + resp + "');");
            speakNeuralResponse(resp);
            return;
        } else if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("lower volume")) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            String resp = "Volume decreased, Sir.";
            runJs("appendLog('JARVIS', '🔉 " + resp + "');");
            speakNeuralResponse(resp);
            return;
        }

        // 4. Phone Dialer
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            String target = query.substring(lower.startsWith("call ") ? 5 : 5).trim();
            dialPhoneNumber(target);
            String resp = "Opening phone dialer for " + target + ", Sir.";
            runJs("appendLog('JARVIS', '📞 " + resp + "');");
            speakNeuralResponse(resp);
            return;
        }

        // 5. App Launchers
        if (lower.contains("open youtube")) {
            launchAppOrUrl("com.google.android.youtube", "https://www.youtube.com");
            speakNeuralResponse("Opening YouTube.");
            return;
        } else if (lower.contains("open whatsapp")) {
            launchApp("com.whatsapp");
            speakNeuralResponse("Opening WhatsApp.");
            return;
        } else if (lower.contains("open spotify")) {
            launchAppOrUrl("com.spotify.music", "https://open.spotify.com");
            speakNeuralResponse("Opening Spotify.");
            return;
        } else if (lower.contains("open chrome") || lower.contains("open browser") || lower.contains("search google")) {
            launchAppOrUrl("com.android.chrome", "https://www.google.com");
            speakNeuralResponse("Opening Chrome.");
            return;
        } else if (lower.contains("open calculator")) {
            launchApp("com.google.android.calculator");
            speakNeuralResponse("Opening Calculator.");
            return;
        } else if (lower.contains("open settings")) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            speakNeuralResponse("Accessing device settings.");
            return;
        } else if (lower.contains("battery status") || lower.contains("battery percentage") || lower.equals("battery")) {
            int batt = getBatteryPercentage();
            String res = "Battery power is currently at " + batt + "%, Sir. Power grid stable.";
            runJs("appendLog('JARVIS', " + JSONObject.quote("🔋 " + res) + ");");
            speakNeuralResponse(res);
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

    // ── GEMINI AI TEXT + VISION API ──
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
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-flash-lite-latest",
                "gemini-3.1-flash-lite-preview",
                "gemini-flash-latest",
                "gemini-pro-latest"
            }, 0);

        } catch (Exception e) {
            runJs("appendLog('ERR', " + JSONObject.quote(e.getMessage()) + ");");
        }
    }

    private void sendWithModelFallback(RequestBody body, String[] models, int modelIndex) {
        if (modelIndex >= models.length) {
            mainHandler.post(() -> {
                runJs("appendLog('ERR', 'All AI models temporarily busy (503). Please retry in a moment.'); setAssistantState('ONLINE', 0.1);");
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
                                speakNeuralResponse(reply);
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

    // ── HIGH-DEFINITION NEURAL VOICE STREAMING SYNTHESIS ──
    private void speakNeuralResponse(String rawText) {
        String cleanText = rawText.replaceAll("[*#_`]", "").trim();
        if (cleanText.isEmpty()) return;

        isSpeaking = true;
        stopListening();

        new Thread(() -> {
            try {
                // Synthesize natural British Tony Stark / Jarvis neural audio
                String langCode = voiceName.equals("en-us") ? "en-us" : (voiceName.equals("en-in") ? "en-in" : "en-uk");
                String ttsUrl = "https://translate.google.com/translate_tts?ie=UTF-8&q=" +
                        URLEncoder.encode(cleanText, "UTF-8") +
                        "&tl=" + langCode + "&client=tw-ob";

                URL url = new URL(ttsUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    File tempAudio = File.createTempFile("neural_voice", ".mp3", getCacheDir());
                    FileOutputStream fos = new FileOutputStream(tempAudio);
                    InputStream is = conn.getInputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                    is.close();

                    mainHandler.post(() -> playAudioFile(tempAudio));
                } else {
                    // Fallback to local TTS
                    mainHandler.post(() -> fallbackTtsSpeak(cleanText));
                }
            } catch (Exception e) {
                mainHandler.post(() -> fallbackTtsSpeak(cleanText));
            }
        }).start();
    }

    private void playAudioFile(File audioFile) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                mainHandler.post(() -> runJs("setAssistantState('SPEAKING', 0.85);"));
                mp.start();
                animateSpeakingMouth();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                isSpeaking = false;
                mp.release();
                mediaPlayer = null;
                audioFile.delete();
                mainHandler.post(() -> {
                    runJs("setAssistantState('ONLINE', 0.1);");
                    // Auto resume continuous conversation if unmuted
                    if (!isMuted) {
                        mainHandler.postDelayed(MainActivity.this::startContinuousListening, 300);
                    }
                });
            });
            mediaPlayer.prepare();
        } catch (Exception e) {
            fallbackTtsSpeak(audioFile.getName());
        }
    }

    private void animateSpeakingMouth() {
        new Thread(() -> {
            while (isSpeaking && mediaPlayer != null && mediaPlayer.isPlaying()) {
                float level = 0.35f + (float) (Math.random() * 0.6);
                mainHandler.post(() -> runJs("audioLevel = " + level + ";"));
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private void fallbackTtsSpeak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId_" + System.currentTimeMillis());
            mainHandler.postDelayed(() -> {
                isSpeaking = false;
                if (!isMuted) startContinuousListening();
            }, 3000);
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
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        super.onDestroy();
    }
}
