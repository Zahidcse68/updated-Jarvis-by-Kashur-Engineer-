package org.kashurengineer.markliv;

import android.Manifest;
import android.app.AlertDialog;
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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
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
    private static final String KEY_USER_NAME = "user_name";

    private UltronReactorView reactorView;
    private TextView statusText;
    private TextView logTextView;
    private ScrollView logScrollView;
    private Button btnMic;
    private Button btnSendText;
    private EditText textCommandInput;
    private ImageButton btnSettings;
    private ImageButton btnCamera;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        reactorView = findViewById(R.id.reactorView);
        statusText = findViewById(R.id.statusText);
        logTextView = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);
        btnMic = findViewById(R.id.btnMic);
        btnSendText = findViewById(R.id.btnSendText);
        textCommandInput = findViewById(R.id.textCommandInput);
        btnSettings = findViewById(R.id.btnSettings);
        btnCamera = findViewById(R.id.btnCamera);

        tts = new TextToSpeech(this, this);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        apiKey = prefs.getString(KEY_GEMINI_API, "");

        checkPermissions();
        initSpeechRecognizer();
        setupListeners();

        if (apiKey.isEmpty()) {
            showSettingsDialog();
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
                    mainHandler.post(() -> {
                        reactorView.setSpeaking(true);
                        reactorView.setAudioLevel(0.7f);
                        statusText.setText("SPEAKING · JARVIS ACTIVE");
                    });
                }

                @Override
                public void onDone(String utteranceId) {
                    mainHandler.post(() -> {
                        reactorView.setSpeaking(false);
                        reactorView.setAudioLevel(0.1f);
                        statusText.setText("ONLINE · READY");
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.post(() -> {
                        reactorView.setSpeaking(false);
                        reactorView.setAudioLevel(0.1f);
                        statusText.setText("ONLINE · READY");
                    });
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
                    mainHandler.post(() -> {
                        statusText.setText("LISTENING · SPEAK NOW...");
                        reactorView.setAudioLevel(0.5f);
                    });
                }

                @Override
                public void onBeginningOfSpeech() {
                    mainHandler.post(() -> reactorView.setAudioLevel(0.85f));
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    float norm = Math.max(0.1f, Math.min(1.0f, (rmsdB + 2f) / 10f));
                    mainHandler.post(() -> reactorView.setAudioLevel(norm));
                }

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    mainHandler.post(() -> {
                        isListening = false;
                        btnMic.setText("🎤 TAP TO SPEAK");
                        statusText.setText("PROCESSING...");
                    });
                }

                @Override
                public void onError(int error) {
                    mainHandler.post(() -> {
                        isListening = false;
                        btnMic.setText("🎤 TAP TO SPEAK");
                        statusText.setText("ONLINE · READY");
                    });
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String query = matches.get(0);
                        appendLog("You: " + query);
                        handleUserQuery(query, null);
                    }
                    isListening = false;
                    btnMic.setText("🎤 TAP TO SPEAK");
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}
                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void setupListeners() {
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        btnCamera.setOnClickListener(v -> {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(takePictureIntent, CAMERA_REQ_CODE);
            } else {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
            }
        });

        btnMic.setOnClickListener(v -> {
            if (!isListening) {
                startVoiceRecognition();
            } else {
                stopVoiceRecognition();
            }
        });

        btnSendText.setOnClickListener(v -> {
            String text = textCommandInput.getText().toString().trim();
            if (!text.isEmpty()) {
                appendLog("You: " + text);
                textCommandInput.setText("");
                handleUserQuery(text, null);
            }
        });
    }

    private void startVoiceRecognition() {
        if (speechRecognizer != null) {
            isListening = true;
            btnMic.setText("🛑 LISTENING... (TAP TO STOP)");
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
            btnMic.setText("🎤 TAP TO SPEAK");
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
                    appendLog("📷 [Captured Camera Photo for Analysis]");
                    handleUserQuery("Analyze what you see in this photo and describe it clearly.", imageBitmap);
                }
            }
        }
    }

    private void handleUserQuery(String query, @Nullable Bitmap image) {
        String lower = query.toLowerCase();

        // Android Native Quick App Intent Triggers
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

        // Send to Gemini Live / Flash API
        queryGeminiAPI(query, image);
    }

    private void queryGeminiAPI(String prompt, @Nullable Bitmap image) {
        if (apiKey.isEmpty()) {
            appendLog("ERR: No Gemini API Key. Tap ⚙️ to enter your key.");
            showSettingsDialog();
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

            statusText.setText("THINKING · COMMUNICATING...");

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    mainHandler.post(() -> {
                        statusText.setText("CONNECTION FAILED");
                        appendLog("ERR: " + e.getMessage());
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

                                // Store assistant reply in conversation history
                                JSONObject modelTurn = new JSONObject();
                                modelTurn.put("role", "model");
                                JSONArray modelParts = new JSONArray();
                                modelParts.put(new JSONObject().put("text", reply));
                                modelTurn.put("parts", modelParts);
                                conversationHistory.put(modelTurn);

                                mainHandler.post(() -> {
                                    appendLog("JARVIS: " + reply);
                                    speakOut(reply);
                                });
                                return;
                            }
                        } catch (Exception e) {
                            mainHandler.post(() -> appendLog("Parse Error: " + e.getMessage()));
                        }
                    } else {
                        mainHandler.post(() -> appendLog("API Error: HTTP " + response.code()));
                    }
                }
            });

        } catch (Exception e) {
            appendLog("Error: " + e.getMessage());
        }
    }

    private void speakOut(String text) {
        if (tts != null) {
            String cleanText = text.replaceAll("[*#_`]", "");
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId_" + System.currentTimeMillis());
        }
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ MARK LIV Settings");

        final EditText input = new EditText(this);
        input.setHint("Paste Gemini API Key (AIzaSy...)");
        input.setText(apiKey);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            apiKey = input.getText().toString().trim();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_GEMINI_API, apiKey).apply();
            Toast.makeText(this, "API Key Saved!", Toast.LENGTH_SHORT).show();
            appendLog("SYS: API Key updated. Ready.");
            speakOut("System initialized and ready for commands, Sir.");
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void appendLog(String message) {
        logTextView.append("\n\n" + message);
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
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
