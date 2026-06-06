package com.suboverlay.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.suboverlay.R;
import com.suboverlay.databinding.ActivityMainBinding;
import com.suboverlay.service.SubtitleOverlayService;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private MediaProjectionManager projectionManager;
    private boolean overlayGranted = false;

    // MediaProjection 권한 요청
    private final ActivityResultLauncher<Intent> projectionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                startSubtitleService(result.getResultCode(), result.getData());
            } else {
                Toast.makeText(this, "화면 캡처 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        });

    // 오버레이 권한 확인용
    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Settings.canDrawOverlays(this)) {
                overlayGranted = true;
                requestAudioPermission();
            } else {
                Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        });

    // 마이크 권한
    private final ActivityResultLauncher<String> audioPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                requestMediaProjection();
            } else {
                Toast.makeText(this, "오디오 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        setupUI();
        checkAndRequestPermissions();
    }

    private void setupUI() {
        // 자막 크기 슬라이더
        binding.sliderFontSize.addOnChangeListener((slider, value, fromUser) -> {
            binding.tvFontSizeLabel.setText("글자 크기: " + (int) value + "sp");
            sendSettingsBroadcast();
        });

        // 언어 토글
        binding.toggleLang.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) sendSettingsBroadcast();
        });

        // 서비스 시작/중지 버튼
        binding.btnToggleService.setOnClickListener(v -> {
            if (SubtitleOverlayService.isRunning) {
                stopSubtitleService();
            } else {
                checkAndRequestPermissions();
            }
        });

        // 배경 투명도 슬라이더
        binding.sliderBgAlpha.addOnChangeListener((slider, value, fromUser) -> {
            sendSettingsBroadcast();
        });

        updateButtonState();
    }

    private void checkAndRequestPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해주세요", Toast.LENGTH_LONG).show();
            overlayLauncher.launch(intent);
        } else {
            overlayGranted = true;
            requestAudioPermission();
        }
    }

    private void requestAudioPermission() {
        audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
    }

    private void requestMediaProjection() {
        Intent intent = projectionManager.createScreenCaptureIntent();
        projectionLauncher.launch(intent);
    }

    private void startSubtitleService(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, SubtitleOverlayService.class);
        serviceIntent.putExtra("resultCode", resultCode);
        serviceIntent.putExtra("data", data);
        serviceIntent.putExtra("fontSize", (int) binding.sliderFontSize.getValue());
        serviceIntent.putExtra("bgAlpha", (int) binding.sliderBgAlpha.getValue());
        serviceIntent.putExtra("langMode", getSelectedLang());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        updateButtonState();
        Toast.makeText(this, "자막 오버레이 시작됨 ✓", Toast.LENGTH_SHORT).show();
    }

    private void stopSubtitleService() {
        Intent serviceIntent = new Intent(this, SubtitleOverlayService.class);
        stopService(serviceIntent);
        updateButtonState();
    }

    private void sendSettingsBroadcast() {
        Intent intent = new Intent(SubtitleOverlayService.ACTION_UPDATE_SETTINGS);
        intent.putExtra("fontSize", (int) binding.sliderFontSize.getValue());
        intent.putExtra("bgAlpha", (int) binding.sliderBgAlpha.getValue());
        intent.putExtra("langMode", getSelectedLang());
        sendBroadcast(intent);
    }

    private String getSelectedLang() {
        int id = binding.toggleLang.getCheckedButtonId();
        if (id == R.id.btnLangKo) return "ko";
        if (id == R.id.btnLangEn) return "en";
        return "both"; // 기본: 한+영
    }

    private void updateButtonState() {
        if (SubtitleOverlayService.isRunning) {
            binding.btnToggleService.setText("⏹ 자막 중지");
            binding.btnToggleService.setBackgroundColor(0xFFE53935);
            binding.statusIndicator.setText("● 실행 중");
            binding.statusIndicator.setTextColor(0xFF4CAF50);
        } else {
            binding.btnToggleService.setText("▶ 자막 시작");
            binding.btnToggleService.setBackgroundColor(0xFF5C6BC0);
            binding.statusIndicator.setText("○ 대기");
            binding.statusIndicator.setTextColor(0xFF9E9E9E);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtonState();
    }
}
