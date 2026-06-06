package com.suboverlay.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.suboverlay.R;
import com.suboverlay.ui.MainActivity;

import java.util.ArrayList;
import java.util.Locale;

public class SubtitleOverlayService extends Service {

    public static final String ACTION_UPDATE_SETTINGS = "com.suboverlay.UPDATE_SETTINGS";
    public static boolean isRunning = false;

    private static final String CHANNEL_ID = "subtitle_overlay_channel";
    private static final String TAG = "SubOverlay";

    // UI
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvKo, tvEn, tvOverlayKo, tvOverlayEn;

    // STT
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;

    // MLKit 번역기 (영→한)
    private Translator enKoTranslator;
    private boolean translatorReady = false;

    // 설정
    private int fontSize = 18;
    private int bgAlpha = 180;
    private String langMode = "both";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 설정 업데이트 수신
    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            fontSize = intent.getIntExtra("fontSize", 18);
            bgAlpha = intent.getIntExtra("bgAlpha", 180);
            langMode = intent.getStringExtra("langMode");
            if (langMode == null) langMode = "both";
            applySettings();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        registerReceiver(settingsReceiver, new IntentFilter(ACTION_UPDATE_SETTINGS),
            Context.RECEIVER_NOT_EXPORTED);
        setupTranslator();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        // 포그라운드 알림
        startForeground(1, buildNotification());

        // 설정 읽기
        fontSize = intent.getIntExtra("fontSize", 18);
        bgAlpha = intent.getIntExtra("bgAlpha", 180);
        langMode = intent.getStringExtra("langMode");
        if (langMode == null) langMode = "both";

        // 오버레이 생성
        createOverlay();

        // STT 시작 (마이크 직접)
        startSpeechRecognition();

        return START_STICKY;
    }

    // ── 오버레이 뷰 생성 ──────────────────────────────────────────────────────

    private void createOverlay() {
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.overlay_subtitle, null);

        tvOverlayKo = overlayView.findViewById(R.id.tvOverlayKo);
        tvOverlayEn = overlayView.findViewById(R.id.tvOverlayEn);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 80; // 화면 하단에서 80px 위

        windowManager.addView(overlayView, params);
        applySettings();
    }

    private void applySettings() {
        if (overlayView == null) return;
        mainHandler.post(() -> {
            // 자막 크기
            tvOverlayKo.setTextSize(fontSize);
            tvOverlayEn.setTextSize(fontSize - 3);

            // 배경 투명도
            int bg = Color.argb(bgAlpha, 0, 0, 0);
            tvOverlayKo.setBackgroundColor(bg);
            tvOverlayEn.setBackgroundColor(bg);

            // 언어 모드
            switch (langMode) {
                case "ko":
                    tvOverlayKo.setVisibility(View.VISIBLE);
                    tvOverlayEn.setVisibility(View.GONE);
                    break;
                case "en":
                    tvOverlayKo.setVisibility(View.GONE);
                    tvOverlayEn.setVisibility(View.VISIBLE);
                    break;
                default: // both
                    tvOverlayKo.setVisibility(View.VISIBLE);
                    tvOverlayEn.setVisibility(View.VISIBLE);
            }
        });
    }

    // ── Google STT ────────────────────────────────────────────────────────────

    private void startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer 사용 불가");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                Log.d(TAG, "STT 준비됨");
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // 실시간 중간 결과 표시
                ArrayList<String> partial = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    String text = partial.get(0);
                    showEnglish(text);
                    translateToKorean(text);
                }
            }

            @Override
            public void onResults(Bundle results) {
                // 최종 결과
                ArrayList<String> matches = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    showEnglish(text);
                    translateToKorean(text);
                }
                // 연속 인식을 위해 재시작
                mainHandler.postDelayed(() -> restartListening(), 300);
            }

            @Override public void onError(int error) {
                Log.w(TAG, "STT 오류: " + error);
                isListening = false;
                // 오류 시 재시작 (네트워크 오류 등)
                mainHandler.postDelayed(() -> restartListening(), 1000);
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { isListening = false; }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); // 중간 결과 활성화
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        speechRecognizer.startListening(recognizerIntent);
        isListening = true;
    }

    private void restartListening() {
        if (!isRunning) return;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.startListening(recognizerIntent);
                isListening = true;
            } catch (Exception e) {
                Log.e(TAG, "재시작 실패", e);
            }
        }
    }

    // ── MLKit 번역 ────────────────────────────────────────────────────────────

    private void setupTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build();
        enKoTranslator = Translation.getClient(options);

        // 번역 모델 다운로드 (첫 실행 시)
        enKoTranslator.downloadModelIfNeeded()
            .addOnSuccessListener(v -> {
                translatorReady = true;
                Log.d(TAG, "번역 모델 준비 완료");
            })
            .addOnFailureListener(e -> Log.e(TAG, "번역 모델 다운로드 실패", e));
    }

    private void translateToKorean(String englishText) {
        if (!translatorReady || englishText == null || englishText.isEmpty()) return;

        enKoTranslator.translate(englishText)
            .addOnSuccessListener(koreanText -> showKorean(koreanText))
            .addOnFailureListener(e -> Log.e(TAG, "번역 실패", e));
    }

    // ── 자막 표시 ─────────────────────────────────────────────────────────────

    private void showEnglish(String text) {
        mainHandler.post(() -> {
            if (tvOverlayEn != null) {
                tvOverlayEn.setText(text);
                tvOverlayEn.setAlpha(1f);
            }
        });
    }

    private void showKorean(String text) {
        mainHandler.post(() -> {
            if (tvOverlayKo != null) {
                tvOverlayKo.setText(text);
                tvOverlayKo.setAlpha(1f);
            }
        });
    }

    // ── 알림 ─────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "자막 오버레이", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("실시간 자막 오버레이 서비스");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, SubtitleOverlayService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SubOverlay 실행 중")
            .setContentText("실시간 자막 오버레이 활성화됨")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "중지", stopPi)
            .setOngoing(true)
            .build();
    }

    // ── 정리 ─────────────────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        if (enKoTranslator != null) {
            enKoTranslator.close();
        }
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
