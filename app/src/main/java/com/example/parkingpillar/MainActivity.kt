package com.example.parkingpillar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.parkingpillar.ui.theme.ParkingPillarTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class MainScreen {
    Voice,
    PhotoCapture,
    ParkingDetail
}

class MainActivity : ComponentActivity() {

    // 음성 자동 시작 "신호". 값이 0이면 신호 없음(평소 실행),
    // 0이 아니면 그 타임스탬프마다 새 신호로 간주해 VoiceScreen이 음성 인식을 한 번 시작한다.
    // 매번 다른 값(현재 시각)을 넣어, 같은 신호가 다시 와도 LaunchedEffect가 재실행되게 한다.
    private val autoStartSignal = mutableLongStateOf(0L)

    // Navigation Compose를 새로 도입하지 않고, 현재 필요한 두 화면만 단순 상태로 전환한다.
    // 사진 화면은 마지막 주차 위치에 사진 경로를 붙이는 보조 흐름이라 앱 구조를 크게 흔들 필요가 없다.
    private val currentScreen = mutableStateOf(MainScreen.Voice)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱이 꺼져 있다가 알림 "말하기"로 켜지는 경우: 시작 Intent에서 신호를 읽는다
        handleIntent(intent)
        // 앱을 처음 켤 때, 알림 설정이 ON일 때만 서비스를 시작한다.
        // (OFF로 둔 사용자는 앱을 다시 켜도 알림이 뜨지 않아야 하므로 설정을 먼저 확인)
        lifecycleScope.launch {
            if (notificationEnabledFlow(applicationContext).first()) {
                ParkingService.start(applicationContext)
            }
        }
        enableEdgeToEdge()
        setContent {
            ParkingPillarTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen.value) {
                        MainScreen.Voice -> {
                            VoiceScreen(
                                autoStartSignal = autoStartSignal.longValue,
                                onRequestPhotoCapture = {
                                    // 사진 촬영 화면에서 돌아올 때 같은 autoStartSignal로
                                    // 음성 인식이 다시 자동 시작되지 않도록 촬영 진입 전에 신호를 비운다.
                                    autoStartSignal.longValue = 0L
                                    currentScreen.value = MainScreen.PhotoCapture
                                },
                                onOpenParkingDetail = {
                                    currentScreen.value = MainScreen.ParkingDetail
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        MainScreen.PhotoCapture -> {
                            PhotoCaptureScreen(
                                onPhotoSaved = { photoPath ->
                                    lifecycleScope.launch {
                                        // 촬영된 사진은 독립 기록이 아니라 현재 마지막 주차 위치의 보조 정보다.
                                        // attachPhotoToLastParking이 기존 text/savedAtMillis는 유지하고 photoPath만 붙인다.
                                        attachPhotoToLastParking(applicationContext, photoPath)
                                        // 사진 경로를 DataStore에 붙인 뒤 원래 Voice 화면으로 돌아간다.
                                        // 이번 단계에서는 메인 화면에 사진을 표시하지 않고 저장만 완료한다.
                                        currentScreen.value = MainScreen.Voice
                                    }
                                },
                                onBack = {
                                    // 사용자가 취소/뒤로가기를 누르면 사진을 붙이지 않고 원래 Voice 화면으로 복귀한다.
                                    currentScreen.value = MainScreen.Voice
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }

                        MainScreen.ParkingDetail -> {
                            ParkingDetailScreen(
                                onBack = {
                                    currentScreen.value = MainScreen.Voice
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    // 이미 떠 있던 MainActivity가 알림 "말하기"(CLEAR_TOP)로 다시 불려올 때 호출된다.
    // onCreate는 이 경우 다시 안 불리므로, 여기서도 신호를 처리해야 자동 시작이 동작한다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 시스템이 보관 중인 현재 Intent를 새 것으로 교체(권장 동작)
        setIntent(intent)
        handleIntent(intent)
    }

    // Intent의 extra를 읽어, "말하기" 신호면 autoStartSignal을 새 타임스탬프로 갱신한다.
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_VOICE, false) == true) {
            currentScreen.value = MainScreen.Voice
            autoStartSignal.longValue = System.currentTimeMillis()
            // 한 번 소비한 신호는 extra에서 제거 → 화면 회전 등으로 Intent가 재사용돼도 중복 실행 방지
            intent.removeExtra(EXTRA_AUTO_START_VOICE)
        }
    }
}
