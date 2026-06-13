package com.example.parkingpillar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

private const val PHOTO_DIR = "parking_photos"

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 카메라 권한 부여 여부 상태
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 런타임 권한 요청 런처 (안드로이드 표준 API)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraContent(modifier = Modifier.fillMaxSize())
        } else {
            PermissionRequest(
                modifier = Modifier.fillMaxSize(),
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }
    }
}

@Composable
private fun PermissionRequest(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "주차 위치를 찍으려면 카메라 권한이 필요해요.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("카메라 권한 허용하기")
        }
    }
}

@Composable
private fun CameraContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 촬영 후 저장된 사진의 Uri (null이면 아직 미리보기 없음)
    var savedPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // OCR 인식 결과 (사용자가 직접 수정 가능) 및 진행 상태
    var ocrText by remember { mutableStateOf("") }
    var isRecognizing by remember { mutableStateOf(false) }
    // 큰 글자 위주로 추려낸 기둥 번호 후보 (큰 순서)
    var ocrCandidates by remember { mutableStateOf<List<String>>(emptyList()) }

    // ImageCapture 유즈케이스는 컴포지션 동안 유지
    val imageCapture = remember { ImageCapture.Builder().build() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 실시간 카메라 미리보기
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp)),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                bindCameraPreview(ctx, previewView, lifecycleOwner, imageCapture)
                previewView
            }
        )

        Button(
            onClick = {
                takePhoto(
                    context = context,
                    imageCapture = imageCapture,
                    onSaved = { uri ->
                        savedPhotoUri = uri
                        // 저장 직후 자동으로 온디바이스 OCR 실행
                        isRecognizing = true
                        ocrText = ""
                        ocrCandidates = emptyList()
                        recognizeText(
                            context = context,
                            uri = uri,
                            onResult = { candidates ->
                                ocrCandidates = candidates
                                // 가장 큰 후보를 입력칸에 기본으로 채움 (없으면 빈 칸)
                                ocrText = candidates.firstOrNull().orEmpty()
                                isRecognizing = false
                            },
                            onError = {
                                // 실패 시 빈 칸 유지 → 사용자가 직접 입력
                                ocrText = ""
                                ocrCandidates = emptyList()
                                isRecognizing = false
                            }
                        )
                    }
                )
            },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("주차 위치 촬영")
        }

        // 촬영 전에만 보이는 안심 문구
        if (savedPhotoUri == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "이 사진은 기기 안에서만 보관되며, 외부로 전송되지 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 촬영된 사진 미리보기 + 안내 문구
        savedPhotoUri?.let { uri ->
            Text(
                text = "방금 저장한 사진",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            AsyncImage(
                model = uri,
                contentDescription = "저장된 주차 위치 사진",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
            )
            Text(
                text = "이 사진은 언제든 삭제할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            // OCR 영역: 인식 중 로딩 표시 → 끝나면 수정 가능한 입력칸
            if (isRecognizing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "글자 인식 중...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Text(
                    text = "인식된 기둥 번호 (직접 수정 가능)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                )
                // 큰 글자 후보 칩 — 탭하면 입력칸에 채워짐
                if (ocrCandidates.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ocrCandidates.forEach { candidate ->
                            AssistChip(
                                onClick = { ocrText = candidate },
                                label = { Text(candidate) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = ocrText,
                    onValueChange = { ocrText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 200.dp),
                    singleLine = false,
                    placeholder = { Text("예: B2, C-14") }
                )
            }
        }
    }
}

/** CameraProvider를 가져와 Preview + ImageCapture 유즈케이스를 라이프사이클에 바인딩한다. */
private fun bindCameraPreview(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCapture: ImageCapture,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        try {
            // 기존 바인딩 해제 후 후면 카메라로 재바인딩
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            // 바인딩 실패 시 미리보기만 비어 보이게 됨 (1단계에서는 별도 UI 처리 생략)
            e.printStackTrace()
        }
    }, mainExecutor(context))
}

/** 앱 전용 내부 저장소(filesDir/parking_photos)에 JPEG로 사진을 저장한다. */
private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (Uri) -> Unit,
) {
    // 앱 전용 내부 저장소 — 다른 앱이 접근 불가
    val photoDir = File(context.filesDir, PHOTO_DIR).apply { mkdirs() }
    val fileName = "pillar_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis()) +
        ".jpg"
    val photoFile = File(photoDir, fileName)

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        mainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(Uri.fromFile(photoFile))
            }

            override fun onError(exception: ImageCaptureException) {
                exception.printStackTrace()
            }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────
// OCR 후보 점수 계산용 튜닝 상수 (전부 여기서 조정 가능)
// ──────────────────────────────────────────────────────────────────────────

// 기둥 번호 패턴: 영문 1~2자 + (공백/하이픈 선택) + 숫자 1~3자  (예: B2, C-14, D11)
private val PILLAR_PATTERN = Regex("^[A-Za-z]{1,2}[\\s-]?\\d{1,3}$")
// 숫자만 있는 짧은 패턴 (예: 14) — 약한 가산점
private val NUMBER_ONLY_PATTERN = Regex("^\\d{1,3}$")
// 영문/숫자/공백/하이픈 — 이 외 문자는 "특수문자"로 보고 감점
private val ALLOWED_CHARS = Regex("[A-Za-z0-9 \\-]")

private const val WEIGHT_PATTERN_FULL = 100f   // 기둥 번호 패턴 완전 일치 가산점
private const val WEIGHT_PATTERN_NUMBER = 30f   // 숫자만 일치 시 가산점
private const val WEIGHT_SIZE = 50f             // 크기 점수 최대치 (정규화높이 0~1에 곱함)
private const val WEIGHT_CENTER = 30f           // 중앙 근접 점수 최대치 (근접도 0~1에 곱함)

private const val MAX_REASONABLE_LEN = 10       // 이 글자수 초과분에 길이 감점
private const val PENALTY_PER_EXTRA_CHAR = -6f  // 초과 글자 1개당 감점
private const val PENALTY_PER_SPECIAL_CHAR = -8f // 허용 외 특수문자 1개당 감점

// 보여줄 후보 최대 개수
private const val MAX_CANDIDATES = 5

/**
 * 저장된 사진을 ML Kit 온디바이스 Text Recognition에 넘겨 글자를 인식한다.
 * 라틴 문자 모델은 앱에 번들로 포함되어 인터넷 없이 동작한다.
 * 인식 후 글자 크기(boundingBox 높이) 기준으로 큰 글자 위주의 후보 목록을 돌려준다.
 */
private fun recognizeText(
    context: Context,
    uri: Uri,
    onResult: (List<String>) -> Unit,
    onError: (Exception) -> Unit,
) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // 점수 계산에 이미지 폭/높이를 써서 "중앙 근접도"를 정규화한다
                onResult(rankPillarCandidates(visionText, image.width, image.height))
            }
            .addOnFailureListener { e -> onError(e) }
    } catch (e: Exception) {
        // 이미지 로드 실패 등
        onError(e)
    }
}

/**
 * 인식된 각 Line에 점수를 매겨 기둥 번호일 가능성이 높은 순으로 정렬한다.
 * 패턴/크기/중앙근접/감점을 합산하며, 패턴에 안 맞아도 후보에서 빼지 않고 점수만 낮춘다.
 * 점수 내림차순 → 중복 제거 → 상위 [MAX_CANDIDATES]개 텍스트를 반환.
 *
 * @param imgWidth  원본 이미지 폭 (중앙 근접도 정규화에 사용)
 * @param imgHeight 원본 이미지 높이
 */
private fun rankPillarCandidates(
    visionText: com.google.mlkit.vision.text.Text,
    imgWidth: Int,
    imgHeight: Int,
): List<String> {
    // 박스가 있는 Line만 (텍스트, boundingBox) 형태로 수집
    val lines = visionText.textBlocks
        .flatMap { it.lines }
        .mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val text = line.text.trim()
            if (text.isEmpty() || box.height() <= 0) null else text to box
        }

    if (lines.isEmpty()) return emptyList()

    // 크기 점수를 0~1로 정규화하기 위한 기준값 (이 사진에서 가장 큰 Line 높이)
    val maxHeight = lines.maxOf { it.second.height() }.toFloat()

    // 이미지 중심 좌표. 폭/높이가 0이면(이상값) 1로 막아 0 나눗셈 방지
    val safeW = if (imgWidth > 0) imgWidth.toFloat() else 1f
    val safeH = if (imgHeight > 0) imgHeight.toFloat() else 1f
    val imgCx = safeW / 2f
    val imgCy = safeH / 2f
    // 중심에서 모서리까지의 최대 정규화 거리 (= √(0.5² + 0.5²) ≈ 0.707). 근접도 환산 분모.
    val maxDist = kotlin.math.sqrt(0.5f * 0.5f + 0.5f * 0.5f)

    // 각 Line의 종합 점수를 계산
    val scored = lines.map { (text, box) ->
        var score = 0f

        // ── 1) 패턴 점수 ────────────────────────────────────────────────
        // 기둥 번호 형태(B2, C-14, D11)면 큰 가산점, 숫자만이면 약한 가산점
        score += when {
            PILLAR_PATTERN.matches(text) -> WEIGHT_PATTERN_FULL
            NUMBER_ONLY_PATTERN.matches(text) -> WEIGHT_PATTERN_NUMBER
            else -> 0f
        }

        // ── 2) 크기 점수 ────────────────────────────────────────────────
        // 가장 큰 글자 대비 높이 비율(0~1)에 가중치를 곱함 → 클수록 높은 점수
        val sizeRatio = box.height() / maxHeight
        score += sizeRatio * WEIGHT_SIZE

        // ── 3) 중앙 근접 점수 ───────────────────────────────────────────
        // Line 박스 중심과 이미지 중심의 거리를 가로·세로 각각 이미지 크기로 나눠
        // (0~1 스케일로 통일) 유클리드 거리 계산
        val boxCx = box.exactCenterX()
        val boxCy = box.exactCenterY()
        val dx = (boxCx - imgCx) / safeW
        val dy = (boxCy - imgCy) / safeH
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        // 근접도: 정중앙=1, 모서리=0 (음수가 되지 않게 0으로 하한)
        val closeness = (1f - dist / maxDist).coerceAtLeast(0f)
        score += closeness * WEIGHT_CENTER

        // ── 4) 감점 ─────────────────────────────────────────────────────
        // (a) 너무 긴 텍스트(문장형 라벨 등): 기준 글자수 초과분에 글자당 감점
        if (text.length > MAX_REASONABLE_LEN) {
            val extra = text.length - MAX_REASONABLE_LEN
            score += extra * PENALTY_PER_EXTRA_CHAR
        }
        // (b) 특수문자: 허용 문자(영문/숫자/공백/하이픈) 외 글자 수만큼 감점
        val specialCount = text.count { !ALLOWED_CHARS.matches(it.toString()) }
        score += specialCount * PENALTY_PER_SPECIAL_CHAR

        text to score
    }

    // 점수 내림차순 정렬 → 중복 텍스트 제거 → 상위 N개 텍스트만 반환
    return scored
        .sortedByDescending { it.second }
        .map { it.first }
        .distinct()
        .take(MAX_CANDIDATES)
}

private fun mainExecutor(context: Context): Executor =
    ContextCompat.getMainExecutor(context)
