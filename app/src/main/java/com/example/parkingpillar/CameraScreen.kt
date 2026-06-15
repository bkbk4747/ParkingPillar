package com.example.parkingpillar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

private const val PHOTO_DIR = "parking_photos"

@Composable
fun PhotoCaptureScreen(
    onPhotoSaved: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            PhotoCaptureContent(
                onPhotoSaved = onPhotoSaved,
                onBack = onBack,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CameraPermissionRequest(
                modifier = Modifier.fillMaxSize(),
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack
            )
        }
    }
}

@Composable
private fun CameraPermissionRequest(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "카메라 권한이 꺼져 있어요. 앱 설정에서 권한을 허용해 주세요.",
            style = MaterialTheme.typography.bodyLarge
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("돌아가기")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRequest) {
                Text("카메라 권한 허용하기")
            }
        }
    }
}

@Composable
private fun PhotoCaptureContent(
    onPhotoSaved: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 이 화면은 예전 OCR 실험 화면이 아니라, 마지막 주차 위치에 붙일 사진만 촬영한다.
    // OCR 인식/후보 랭킹은 이번 요구사항의 범위가 아니므로 제거해 카메라 책임만 남겼다.
    DisposableEffect(Unit) {
        onDispose {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                { cameraProviderFuture.get().unbindAll() },
                mainExecutor(context)
            )
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "주차 위치 사진 추가",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )

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

        Text(
            text = "방금 저장한 주차 위치에 사진을 추가합니다.\n사진은 갤러리에 저장되지 않고 앱 안에만 보관됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )

        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            OutlinedButton(
                onClick = onBack,
                enabled = !isSaving
            ) {
                Text("취소")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !isSaving,
                onClick = {
                    isSaving = true
                    errorMessage = null
                    takePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        onSaved = { photoPath ->
                            // 촬영이 끝나면 MainActivity에서 photoPath를 마지막 위치에 붙이고
                            // 원래 Voice 화면으로 돌아간다. 사진 화면은 저장만 담당한다.
                            onPhotoSaved(photoPath)
                        },
                        onError = {
                            // 사진 저장에 실패해도 바로 원래 화면으로 돌아가지 않는다.
                            // 사용자가 실패 메시지를 보고 다시 촬영하거나 취소할 수 있어야 한다.
                            isSaving = false
                            errorMessage = "사진 저장에 실패했어요. 다시 촬영해 주세요."
                        }
                    )
                }
            ) {
                Text(if (isSaving) "저장 중..." else "사진 촬영")
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
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }, mainExecutor(context))
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (String) -> Unit,
    onError: (ImageCaptureException) -> Unit,
) {
    // 사진은 갤러리에 노출되는 독립 미디어가 아니라 마지막 주차 위치의 보조 정보다.
    // 그래서 앱 전용 내부 저장소(filesDir)에 저장해 다른 앱/갤러리에서 보이지 않게 한다.
    val photoDir = File(context.filesDir, PHOTO_DIR).apply { mkdirs() }
    val fileName = "parking_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis()) +
        ".jpg"
    val photoFile = File(photoDir, fileName)

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        mainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(photoFile.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}

private fun mainExecutor(context: Context): Executor =
    ContextCompat.getMainExecutor(context)
