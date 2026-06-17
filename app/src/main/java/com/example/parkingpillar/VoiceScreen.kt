package com.example.parkingpillar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VoiceScreen(
    autoStartSignal: Long = 0L,
    onRequestPhotoCapture: () -> Unit,
    onOpenParkingDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    //한번 정하면 못바꿈.
    val context = LocalContext.current

    // 마이크 권한 부여 여부 상태 (카메라 권한과 동일한 방식)
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 런타임 권한 요청 런처 (안드로이드 표준 API)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasAudioPermission) {
            VoiceContent(
                autoStartSignal = autoStartSignal,
                onRequestPhotoCapture = onRequestPhotoCapture,
                onOpenParkingDetail = onOpenParkingDetail,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PermissionRequestVoice(
                modifier = Modifier.fillMaxSize(),
                onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
        }
    }
}

@Composable
private fun PermissionRequestVoice(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "주차 위치를 말로 기록하려면 마이크 권한이 필요해요.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("마이크 권한 허용하기")
        }
    }
}

@Composable
private fun VoiceContent(
    autoStartSignal: Long = 0L,
    onRequestPhotoCapture: () -> Unit,
    onOpenParkingDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // DataStore에 저장된 마지막 주차 위치를 구독한다.
    // collectAsState: Flow가 새 값을 방출할 때마다 자동으로 재구성(recompose)된다.
    // → 저장하면 즉시 갱신되고, 앱을 다시 켜면 디스크에서 읽어와 복원된다.
    val lastParking by lastParkingFlow(context).collectAsState(initial = null)

    // 상태바 알림 표시 on/off 설정을 구독 (위치 데이터와 무관한 별개 설정)
    val notificationEnabled by notificationEnabledFlow(context).collectAsState(initial = true)

    // 저장(suspend)을 호출하기 위한 코루틴 스코프 (이 컴포저블 생명주기에 묶임)
    val scope = rememberCoroutineScope()

    // ── 알림 권한 처리 (마이크 권한과 동일한 패턴) ──────────────────────
    // Android 13(API 33)+ 만 POST_NOTIFICATIONS 런타임 권한이 필요.
    // 그 미만 버전은 권한 개념이 없으므로 항상 허용된 것으로 본다.
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    // 권한이 방금 거부됐는지 — 안내 문구 노출용
    var notificationDenied by remember { mutableStateOf(false) }
    var startNotificationServiceAfterPermission by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        notificationDenied = !granted
        // 권한을 얻은 직후에도 앱 내 알림 설정이 ON일 때만 전경 서비스를 시작한다.
        // OFF 상태에서는 권한이 있어도 알림을 다시 살리면 안 된다.
        if (granted && (notificationEnabled || startNotificationServiceAfterPermission)) {
            ParkingService.start(context)
        }
        startNotificationServiceAfterPermission = false
    }

    // 인식 중 여부(버튼 비활성/로딩 표시용)와 인식 결과(사용자가 수정 가능)
    var isListening by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("") }
    // 마지막 시도가 실패/무결과였는지 — "다시 말하기" 안내 노출용
    var showRetryHint by remember { mutableStateOf(false) }
    // 사진은 독립 기록이 아니라 방금 저장한 주차 위치에 붙는 보조 정보다.
    // 그래서 항상 보이는 독립 사진 버튼 대신, 위치 저장이 끝난 직후에만 추가 여부를 묻는다.
    var showPhotoPrompt by remember { mutableStateOf(false) }

    // ── SpeechRecognizer 생성 및 정리 ────────────────────────────────────
    // SpeechRecognizer는 시스템 자원(마이크/인식 서비스)에 연결되는 객체라,
    // 화면이 사라질 때 반드시 destroy()로 해제해야 누수가 없다.
    // DisposableEffect(Unit): 이 컴포저블이 처음 화면에 올라올 때 블록 1회 실행,
    // 화면에서 사라질 때(onDispose) 정리 블록 실행 — 라이프사이클에 묶는 표준 패턴.
    val speechRecognizer = remember {
        // 기기에 음성 인식 기능이 없으면 null일 수 있으나, 대부분의 폰은 지원
        SpeechRecognizer.createSpeechRecognizer(context)
    }

    // 인식 리스너 안에서 최신 상태 갱신 함수를 안전하게 쓰기 위한 래퍼.
    // rememberUpdatedState: 리스너는 한 번만 등록되지만, 그 안에서 항상
    // "가장 최근" 콜백을 참조하도록 보장해준다 (오래된 값 캡처 방지).
    val onResultUpdated by rememberUpdatedState { text: String ->
        recognizedText = text
        isListening = false
        // 결과가 비었으면 다시 시도하도록 안내
        showRetryHint = text.isBlank()
    }
    val onErrorUpdated by rememberUpdatedState {
        // 인식 실패: 듣기 종료하고 재시도 안내 노출 (입력칸은 빈 채로 직접 입력 가능)
        isListening = false
        showRetryHint = true
    }

    DisposableEffect(Unit) {
        // SpeechRecognizer가 인식 과정에서 호출하는 콜백 모음
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // 네트워크 오류, 무음(no match), 타임아웃 등 모든 에러를 한데 처리
                onErrorUpdated()
            }

            override fun onResults(results: Bundle?) {
                // 최종 인식 결과: 후보 문자열 리스트 중 첫 번째(가장 가능성 높은 것)를 사용
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onResultUpdated(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer.setRecognitionListener(listener)

        // onDispose: 화면을 벗어날 때 자원 해제 (필수)
        onDispose {
            speechRecognizer.destroy()
        }
    }

    // 듣기 시작: 인텐트로 한국어/자유 형식 인식을 요청
    val startListening = {
        showRetryHint = false
        showPhotoPrompt = false
        recognizedText = ""
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // 자유 발화 모드 (받아쓰기형)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // 한국어 인식
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.startListening(intent)
    }

    // 알림 "말하기"로 들어온 경우에만 음성 인식을 자동 시작한다.
    // LaunchedEffect(autoStartSignal): 신호 값이 "바뀔 때마다" 블록이 1회 실행된다.
    //  - 평소 직접 앱을 열면 신호는 0 → 아래 if에서 걸러져 자동 시작 안 함.
    //  - 알림으로 열면 MainActivity가 매번 새 타임스탬프를 넣어주므로 값이 바뀌어 실행됨.
    //  - 같은 컴포지션에서 재구성돼도 키가 같으면 재실행되지 않아 중복 시작이 방지된다.
    LaunchedEffect(autoStartSignal) {
        if (autoStartSignal != 0L && !isListening) {
            startListening()
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderSection()

        LastParkingCard(
            lastParking = lastParking,
            isListening = isListening,
            showRetryHint = showRetryHint,
            recognizedText = recognizedText,
            onStartListening = startListening
        )

        if (isListening) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "듣고 있어요...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (showRetryHint && !isListening) {
            Text(
                text = "잘 못 들었어요. 다시 말하거나, 아래에 직접 입력해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ManualInputCard(
            recognizedText = recognizedText,
            onRecognizedTextChange = {
                recognizedText = it
                showPhotoPrompt = false
            },
            onSave = {
                scope.launch {
                    val saved = saveLastParking(context, recognizedText)
                    // DataStore에 저장해도 이미 떠 있는 알림은 자동으로 바뀌지 않으므로,
                    // 같은 알림 ID로 다시 notify해서 내용을 교체해야 한다.
                    // 단, 사용자가 상태바 알림 표시를 OFF로 둔 경우에는 저장 후 알림을 되살리지 않는다.
                    if (notificationEnabled && hasNotificationPermission) {
                        showLastParkingNotification(context, saved)
                    }
                    // 위치 저장이 완료된 뒤에만 사진 촬영을 제안한다.
                    // 이렇게 해야 사진이 위치 없이 단독으로 저장되는 흐름을 만들지 않는다.
                    showPhotoPrompt = true
                }
            },
            onOpenParkingDetail = onOpenParkingDetail
        )

        if (showPhotoPrompt) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = parkingCardColors()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "주차 위치가 저장되었어요.\n사진도 추가하시겠습니까?",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                // [사진 촬영]을 누르면 카메라 전용 화면으로 넘어간다.
                                // 촬영 화면은 파일 경로만 반환하고, MainActivity가 그 경로를 마지막 위치에 연결한다.
                                showPhotoPrompt = false
                                onRequestPhotoCapture()
                            }
                        ) {
                            Text("사진 촬영")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                // [괜찮아요]를 누르면 사진 없이 위치만 저장된 상태를 그대로 유지한다.
                                showPhotoPrompt = false
                            }
                        ) {
                            Text("괜찮아요")
                        }
                    }
                }
            }
        }

        if (notificationDenied) {
            Text(
                text = "알림 권한이 꺼져 있어요. 설정에서 알림 권한을 켜주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        NotificationSettingCard(
            notificationEnabled = notificationEnabled,
            onNotificationEnabledChange = { enabled ->
                scope.launch {
                    // 1) 설정값을 먼저 저장 (앱 재실행/부팅 후에도 선택 유지)
                    setNotificationEnabled(context, enabled)
                    // 2) 설정에 맞춰 서비스 시작/정지 (위치 데이터는 건드리지 않음)
                    if (enabled) {
                        if (hasNotificationPermission) {
                            ParkingService.start(context)
                        } else {
                            startNotificationServiceAfterPermission = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        startNotificationServiceAfterPermission = false
                        ParkingService.stop(context)
                    }
                }
            }
        )
    }
}

@Composable
private fun HeaderSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "내차기둥",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "주차 위치를 쉽게 저장하고, 잊지 않게 알려드려요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun LastParkingCard(
    lastParking: LastParking?,
    isListening: Boolean,
    showRetryHint: Boolean,
    recognizedText: String,
    onStartListening: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = parkingCardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            if (lastParking == null) {
                Text(
                    text = "🅿️ 아직 저장된 주차 위치가 없어요",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "음성으로 주차 위치를 기록해 보세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    text = "🅿️ 마지막 주차 위치",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = lastParking.text,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = formatSavedAt(lastParking.savedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Button(
                onClick = onStartListening,
                enabled = !isListening,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
            ) {
                Text(
                    if (recognizedText.isBlank() && !showRetryHint) {
                        "🎙 기둥 위치를 말해보세요!"
                    } else {
                        "🎙 다시 말해주시겠어요?"
                    }
                )
            }
        }
    }
}

@Composable
private fun ManualInputCard(
    recognizedText: String,
    onRecognizedTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpenParkingDetail: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = parkingCardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "주차 위치 직접 수정 가능",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "음성 인식 결과를 직접 수정할 수 있어요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = recognizedText,
                onValueChange = onRecognizedTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 200.dp)
                    .padding(top = 12.dp),
                singleLine = false,
                placeholder = { Text("예: 지하 2층 D2") }
            )
            Button(
                onClick = onSave,
                enabled = recognizedText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("저장")
            }
            OutlinedButton(
                onClick = onOpenParkingDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("주차 위치 자세히 보기")
            }
        }
    }
}

@Composable
private fun NotificationSettingCard(
    notificationEnabled: Boolean,
    onNotificationEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = parkingCardColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "상태바에 주차 위치 표시",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "알림창에서 주차 위치를 항상 확인할 수 있어요!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = notificationEnabled,
                onCheckedChange = onNotificationEnabledChange
            )
        }
    }
}

@Composable
private fun parkingCardColors(): CardColors =
    CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface
    )

/** 저장 시각(epoch millis)을 "06/16 02:48에 주차하셨어요!" 형식의 문자열로 변환한다. */
private fun formatSavedAt(millis: Long): String {
    val formatter = SimpleDateFormat("MM/dd HH:mm", Locale.KOREAN)
    return "${formatter.format(Date(millis))}에 주차하셨어요!"
}
