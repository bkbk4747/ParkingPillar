package com.example.parkingpillar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ParkingDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lastParking by lastParkingFlow(context).collectAsState(initial = null)

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "저장된 주차 위치",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 24.dp)
        )

        val last = lastParking
        if (last == null) {
            Text(
                text = "아직 저장된 주차 위치가 없어요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = "위치",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = last.text,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 20.dp)
            )

            Text(
                text = "저장 시간",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = formatDetailSavedAt(last.savedAtMillis),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 20.dp)
            )

            Text(
                text = "사진",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            // 사진은 메인 화면이 아니라 상세 화면에서만 확인한다.
            // photoPath는 선택 정보이므로 null, 파일 없음, 디코딩 실패를 모두 안전하게 처리한다.
            ParkingPhoto(
                photoPath = last.photoPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }

        Button(
            onClick = onBack,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text("돌아가기")
        }
    }
}

@Composable
private fun ParkingPhoto(
    photoPath: String?,
    modifier: Modifier = Modifier,
) {
    if (photoPath == null) {
        Text(
            text = "저장된 사진이 없어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    val bitmap = remember(photoPath) {
        loadParkingPhotoBitmap(photoPath)
    }

    if (bitmap == null) {
        Text(
            text = "사진 파일을 불러올 수 없어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "저장된 주차 위치 사진",
            contentScale = ContentScale.FillWidth,
            modifier = modifier
                .sizeIn(maxHeight = 420.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    }
}

private fun loadParkingPhotoBitmap(photoPath: String): Bitmap? =
    runCatching {
        val file = File(photoPath)
        if (!file.isFile) return@runCatching null

        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null

        // CameraX가 저장한 JPEG는 픽셀 자체를 돌리지 않고 EXIF orientation에
        // 회전 정보를 남길 수 있다. BitmapFactory는 이 값을 자동 반영하지 않으므로,
        // 상세 화면에서 보여줄 때만 필요한 만큼 회전해서 사용한다.
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (degrees == 0f) {
            original
        } else {
            Bitmap.createBitmap(
                original,
                0,
                0,
                original.width,
                original.height,
                Matrix().apply { postRotate(degrees) },
                true
            )
        }
    }.getOrNull()

private fun formatDetailSavedAt(millis: Long): String {
    val formatter = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN)
    return formatter.format(Date(millis))
}
