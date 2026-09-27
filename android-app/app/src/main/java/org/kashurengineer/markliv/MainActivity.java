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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private WebView webView;
    private String apiKey = "";
    private String voiceName = "Puck"; // Default Gemini Neural Voice (Jarvis style)
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean isTorchOn = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient;
    private final JSONArray conversationHistory = new JSONArray();

    private AudioTrack liveAudioTrack;
    private MediaPlayer mediaPlayer;

    private static final String SYSTEM_INSTRUCTION =
            "You are JARVIS, the legendary AI assistant for MARK LIV, custom engineered by Kashur Engineer (@kashurengineer). " +
            "You are witty, concise, highly capable, and speak naturally like Tony Stark's JARVIS. " +
            "You can control Android system features (flashlight, volume, dialer, camera, apps), search the web, analyze images, answer questions, and assist the user.";

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
        voiceName = prefs.getString(KEY_VOICE_NAME, "Puck");

        checkPermissions();
        initSpeechRecognizer();
        startTelemetryLoop();
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
        public String getVoiceName() {
            return voiceName;
        }

        @JavascriptInterface
        public void saveVoiceName(String name) {
            voiceName = name.trim();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_VOICE_NAME, voiceName).apply();
            speakOut("Voice preset updated to " + voiceName + ", Sir.");
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
            tts.setLanguage(Locale.US);
            tts.setPitch(0.88f);
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

    // ── SYSTEM CONTROLS & COMMAND ROUTER ──
    private void handleUserQuery(String query, @Nullable Bitmap image) {
        String lower = query.toLowerCase().trim();

        // 1. Flashlight / Torch Control
        if (lower.contains("turn on torch") || lower.contains("torch on") || lower.contains("turn on flashlight") || lower.contains("flashlight on") || lower.equals("light on")) {
            setTorchMode(true);
            speakOut("Flashlight activated, Sir.");
            runJs("appendLog('JARVIS', '🔦 Flashlight turned ON.');");
            return;
        } else if (lower.contains("turn off torch") || lower.contains("torch off") || lower.contains("turn off flashlight") || lower.contains("flashlight off") || lower.equals("light off")) {
            setTorchMode(false);
            speakOut("Flashlight deactivated, Sir.");
            runJs("appendLog('JARVIS', '🔦 Flashlight turned OFF.');");
            return;
        }

        // 2. Volume Controls
        if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("raise volume")) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            speakOut("Volume increased, Sir.");
            runJs("appendLog('JARVIS', '🔊 Volume increased.');");
            return;
        } else if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("lower volume")) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            speakOut("Volume decreased, Sir.");
            runJs("appendLog('JARVIS', '🔉 Volume decreased.');");
            return;
        } else if (lower.contains("mute") || lower.contains("silence")) {
            setVolumePercent(0);
            speakOut("Audio muted, Sir.");
            runJs("appendLog('JARVIS', '🔇 Audio muted.');");
            return;
        }

        // 3. Phone Call / Dialing
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            String target = query.substring(lower.startsWith("call ") ? 5 : 5).trim();
            dialPhoneNumber(target);
            speakOut("Initiating dialer for " + target + ", Sir.");
            runJs("appendLog('JARVIS', '📞 Dialing " + target + "...');");
            return;
        }

        // 4. Native App Launchers
        if (lower.contains("open youtube")) {
            speakOut("Opening YouTube.");
            launchAppOrUrl("com.google.android.youtube", "https://www.youtube.com");
            return;
        } else if (lower.contains("open whatsapp")) {
            speakOut("Opening WhatsApp.");
            launchApp("com.whatsapp");
            return;
        } else if (lower.contains("open spotify")) {
            speakOut("Opening Spotify.");
            launchAppOrUrl("com.spotify.music", "https://open.spotify.com");
            return;
        } else if (lower.contains("open chrome") || lower.contains("open browser") || lower.contains("search google")) {
            speakOut("Opening Chrome.");
            launchAppOrUrl("com.android.chrome", "https://www.google.com");
            return;
        } else if (lower.contains("open maps") || lower.contains("navigation")) {
            speakOut("Opening Maps.");
            launchAppOrUrl("com.google.android.apps.maps", "https://maps.google.com");
            return;
        } else if (lower.contains("open calculator")) {
            speakOut("Opening Calculator.");
            launchApp("com.google.android.calculator");
            return;
        } else if (lower.contains("open settings") || lower.contains("wifi settings") || lower.contains("bluetooth")) {
            speakOut("Accessing system settings.");
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return;
        } else if (lower.contains("battery status") || lower.contains("battery percentage") || lower.equals("battery")) {
            int batt = getBatteryPercentage();
            String res = "Battery power is at " + batt + "%, Sir. All power cells nominal.";
            speakOut(res);
            runJs("appendLog('JARVIS', " + JSONObject.quote("🔋 " + res) + ");");
            return;
        }

        // 5. Send to Gemini AI Engine
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

    private void setVolumePercent(int percent) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = (int) ((percent / 100f) * max);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void dialPhoneNumber(String phone) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open dialer", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchApp(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "App not installed: " + packageName, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchAppOrUrl(String packageName, String fallbackUrl) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            startActivity(intent);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)));
        }
    }

    private int getBatteryPercentage() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                return (int) ((level / (float) scale) * 100);
            }
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

    // ── GEMINI AI REQUEST WITH NEURAL VOICE GENERATION ──
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

            // Generation config with selected Voice Config
            JSONObject genConfig = new JSONObject();
            JSONArray respModalities = new JSONArray();
            respModalities.put("TEXT");
            respModalities.put("AUDIO");
            genConfig.put("responseModalities", respModalities);

            JSONObject speechCfg = new JSONObject();
            JSONObject voiceCfg = new JSONObject();
            JSONObject prebuilt = new JSONObject();
            prebuilt.put("voiceName", voiceName.isEmpty() ? "Puck" : voiceName);
            voiceCfg.put("prebuiltVoiceConfig", prebuilt);
            speechCfg.put("voiceConfig", voiceCfg);
            genConfig.put("speechConfig", speechCfg);

            bodyJson.put("generationConfig", genConfig);

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
                            byte[] audioBytes = null;
                            String audioMime = "audio/pcm";

                            for (int i = 0; i < partsArr.length(); i++) {
                                JSONObject p = partsArr.getJSONObject(i);
                                if (p.has("text")) {
                                    replyBuilder.append(p.getString("text"));
                                }
                                if (p.has("inlineData")) {
                                    JSONObject inData = p.getJSONObject("inlineData");
                                    String b64 = inData.getString("data");
                                    audioMime = inData.optString("mimeType", "audio/pcm");
                                    audioBytes = Base64.decode(b64, Base64.DEFAULT);
                                }
                            }
                            String reply = replyBuilder.toString().trim();

                            JSONObject modelTurn = new JSONObject();
                            modelTurn.put("role", "model");
                            JSONArray modelParts = new JSONArray();
                            modelParts.put(new JSONObject().put("text", reply));
                            modelTurn.put("parts", modelParts);
                            conversationHistory.put(modelTurn);

                            final byte[] finalAudio = audioBytes;
                            final String finalMime = audioMime;

                            mainHandler.post(() -> {
                                runJs("appendLog('JARVIS', " + JSONObject.quote(reply) + ");");
                                if (finalAudio != null && finalAudio.length > 0) {
                                    playGeminiAudio(finalAudio, finalMime);
                                } else {
                                    speakOut(reply);
                                }
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

                // If 503 / 429 / 404, fallback to next model
                sendWithModelFallback(body, models, modelIndex + 1);
            }
        });
    }

    // ── PLAY GEMINI DIRECT NEURAL AUDIO (24kHz PCM / MP3) ──
    private void playGeminiAudio(byte[] audioData, String mime) {
        new Thread(() -> {
            try {
                if (mime.contains("pcm")) {
                    int sampleRate = 24000;
                    if (mime.contains("rate=")) {
                        Pattern p = Pattern.compile("rate=(\\d+)");
                        Matcher m = p.matcher(mime);
                        if (m.find()) {
                            sampleRate = Integer.parseInt(m.group(1));
                        }
                    }

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
                            .setBufferSizeInBytes(Math.max(bufferSize, audioData.length))
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build();

                    track.write(audioData, 0, audioData.length);
                    mainHandler.post(() -> runJs("setAssistantState('SPEAKING', 0.85)"));
                    track.play();

                    // Calculate playback duration and animate reactor core
                    long durationMs = (long) ((audioData.length / 2.0 / sampleRate) * 1000);
                    long startTime = System.currentTimeMillis();

                    while (System.currentTimeMillis() - startTime < durationMs) {
                        float simLevel = 0.4f + (float) (Math.random() * 0.55);
                        mainHandler.post(() -> runJs("audioLevel = " + simLevel + ";"));
                        Thread.sleep(80);
                    }

                    track.stop();
                    track.release();
                    mainHandler.post(() -> runJs("setAssistantState('ONLINE', 0.1)"));

                } else {
                    // Play encoded audio (WAV / MP3)
                    File tempAudio = File.createTempFile("gemini_voice", ".mp3", getCacheDir());
                    FileOutputStream fos = new FileOutputStream(tempAudio);
                    fos.write(audioData);
                    fos.close();

                    if (mediaPlayer != null) {
                        mediaPlayer.release();
                    }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(tempAudio.getAbsolutePath());
                    mediaPlayer.setOnPreparedListener(mp -> {
                        mainHandler.post(() -> runJs("setAssistantState('SPEAKING', 0.85)"));
                        mp.start();
                    });
                    mediaPlayer.setOnCompletionListener(mp -> {
                        mainHandler.post(() -> runJs("setAssistantState('ONLINE', 0.1)"));
                        mp.release();
                        mediaPlayer = null;
                        tempAudio.delete();
                    });
                    mediaPlayer.prepare();
                }
            } catch (Exception e) {
                // Fallback to TTS if audio track failed
                mainHandler.post(() -> speakOut("Audio playback encountered an issue, Sir."));
            }
        }).start();
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
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        super.onDestroy();
    }
}
