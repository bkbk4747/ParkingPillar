package com.example.parkingpillar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 알림 채널 ID(채널 식별자)와 알림 ID(이 알림 1건의 식별자)
private const val CHANNEL_ID = "parking_location"
// Foreground Service가 startForeground에 넘기는 알림 ID와 동일해야 한 알림으로 묶인다
const val PARKING_NOTIFICATION_ID = 1001

// 알림의 "말하기"로 앱을 열었음을 알리는 Intent extra 키.
// MainActivity가 이 키를 읽어 true면 음성 인식을 자동 시작한다.
const val EXTRA_AUTO_START_VOICE = "com.example.parkingpillar.AUTO_START_VOICE"

fun canShowParkingNotification(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

/**
 * 알림 채널을 생성한다.
 *
 * Android 8(API 26)부터는 모든 알림이 "채널"에 속해야 표시된다.
 * 같은 CHANNEL_ID로 다시 만들어도 기존 채널을 덮어쓸 뿐 새로 쌓이지 않으므로,
 * 알림을 띄우기 직전에 매번 호출해도 안전하다.
 *
 * 채널의 중요도(IMPORTANCE_DEFAULT)는 소리/배너 노출 수준을 정한다.
 * (한 번 만들어진 채널의 중요도는 이후 코드로 못 바꾸고, 사용자가 설정에서만 변경 가능)
 */
private fun ensureNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "주차 위치 알림",                       // 설정 화면에 보이는 채널 이름
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "마지막 주차 위치를 알려주는 알림"
    }
    // 시스템 NotificationManager에 채널 등록
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
}

/**
 * 마지막 주차 위치를 담은 상시 알림 [Notification] 객체를 만들어 돌려준다.
 *
 * Foreground Service가 startForeground에 넘길 알림이 필요하므로, "발송"과 분리해
 * "빌드"만 담당한다. (실제 표시는 startForeground 또는 notify가 한다)
 *
 * @param last 저장된 마지막 위치(없으면 null)
 */
fun buildParkingNotification(context: Context, last: LastParking?): Notification {
    // 1) 채널 보장 — 알림 표시 전 반드시 채널이 존재해야 한다
    ensureNotificationChannel(context)

    // 2) 본문 구성 — 위치가 있으면 "위치 · 저장시각", 없으면 안내 문구
    val contentText = if (last != null) {
        "${last.text} · ${formatNotificationTime(last.savedAtMillis)}"
    } else {
        "아직 저장된 주차 위치가 없어요"
    }

    // 3) "말하기" 버튼을 눌렀을 때 실행할 PendingIntent 준비
    //
    // ── PendingIntent란? ───────────────────────────────────────────────
    // 알림은 우리 앱이 아니라 시스템(상태바/알림 UI) 위에 떠 있다.
    // 그래서 사용자가 알림 버튼을 누르는 "미래의 그 순간"에, 시스템이 우리를
    // 대신해 Activity를 열어줘야 한다. PendingIntent는 바로 그 위임 토큰이다.
    // 즉 "이 Intent를, 나중에, 내(우리 앱) 권한으로 대신 실행해도 좋다"는 허가증.
    //
    // 먼저 실제로 열고 싶은 화면(MainActivity = VoiceScreen)을 가리키는 Intent를 만든다.
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        // NEW_TASK: 알림(앱 밖)에서 Activity를 띄울 때 필요한 새 태스크 플래그
        // CLEAR_TOP: 이미 떠 있던 MainActivity가 있으면 새로 쌓지 않고 그 위로 재사용
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        // "말하기"로 들어왔다는 신호를 실어 보낸다 → MainActivity가 읽어 음성 자동 시작
        putExtra(EXTRA_AUTO_START_VOICE, true)
    }

    val speakPendingIntent = PendingIntent.getActivity(
        context,
        0,
        // requestCode: 여러 PendingIntent를 구분하는 번호 (지금은 1개라 0)
        openAppIntent,
        // ── FLAG는 왜 붙이나? ──────────────────────────────────────────
        // FLAG_IMMUTABLE: 위임받은 시스템이 이 Intent의 내용을 못 바꾸게 잠근다.
        //   Android 12(API 31)+ 부터는 보안상 IMMUTABLE / MUTABLE 중 하나를 반드시 명시해야 한다.
        // FLAG_UPDATE_CURRENT: 같은 PendingIntent가 이미 있으면 새 내용으로 갱신한다.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // 4) deleteIntent 준비 — 알림이 "지워질 때" 시스템이 발사하는 PendingIntent.
    //
    // ── 왜 필요한가? ───────────────────────────────────────────────────
    // Android 14부터는 setOngoing(true) 전경 서비스 알림도 사용자가 스와이프로 지울 수
    // 있다. 서비스는 살아있는데 알림만 사라진다. 손목닥터 등은 이때 "다시 게시"해서
    // 알림을 복구한다. 우리도 deleteIntent로 ParkingService를 ACTION_REPOST로 깨워
    // onStartCommand가 다시 startForeground를 호출 → 알림이 상태바에 다시 붙는다.
    //
    // getService: Activity가 아니라 Service를 실행하는 PendingIntent.
    val repostIntent = Intent(context, ParkingService::class.java).apply {
        action = ParkingService.ACTION_REPOST
    }
    val deletePendingIntent = PendingIntent.getService(
        context,
        1,                       // speakPendingIntent(0)와 구분되는 requestCode
        repostIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // 5) contentIntent 준비 — 알림 "본체"(제목/본문)를 탭했을 때 앱을 여는 PendingIntent.
    //    "말하기" 버튼과 달리 음성 자동 시작 신호(extra)는 넣지 않는다 → 그냥 앱만 연다.
    //    requestCode를 2로 두어 speak(0)/delete(1) PendingIntent와 구분한다.
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        2,
        contentIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // 6) 알림 빌드 — NotificationCompat은 구버전 호환을 알아서 처리해준다
    return NotificationCompat.Builder(context, CHANNEL_ID)
        // 알림 본체를 탭하면 앱(MainActivity)을 연다
        .setContentIntent(contentPendingIntent)
        // 알림이 지워지면 위 PendingIntent가 발사되어 서비스가 알림을 다시 띄운다
        .setDeleteIntent(deletePendingIntent)
        .setSmallIcon(R.mipmap.ic_launcher)      // 상태바 작은 아이콘 (기존 런처 아이콘 재사용)
        .setContentTitle("🚗 내차기둥")
        .setContentText(contentText)
        // 본문이 길어도 펼쳐 볼 수 있게 BigTextStyle 적용
        .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // 구버전(채널 없는 기기)용 우선순위
        // 상시 알림: 스와이프로 잘 지워지지 않게(ongoing), 탭해도 자동으로 안 사라지게
        .setOngoing(true)
        .setAutoCancel(false)
        // 무음: 갱신/재게시 때마다 소리·진동이 울리지 않게 한다(채널 변경 없이 이 알림만 무음)
        .setSilent(true)
        // "말하기" 액션 버튼 — 아이콘(임시: 기본 마이크) + 라벨 + 위 PendingIntent
        .addAction(
            android.R.drawable.ic_btn_speak_now,
            "주차 위치 기록",
            speakPendingIntent
        )
        .build()
}

/**
 * 마지막 주차 위치 알림을 즉시 발송/갱신한다.
 *
 * 같은 PARKING_NOTIFICATION_ID로 notify하면 새 알림이 쌓이지 않고 기존 알림을 덮어쓴다.
 * → Foreground Service가 떠 있는 동안 위치가 바뀌면 이 함수로 알림만 갱신할 수 있다.
 *
 * 주의: Android 13+에서는 POST_NOTIFICATIONS 권한이 있어야 실제로 표시된다.
 * 권한이 없으면 notify() 호출은 조용히 무시된다.
 */
fun showLastParkingNotification(context: Context, last: LastParking?) {
    val notification = buildParkingNotification(context, last)
    NotificationManagerCompat.from(context).notify(PARKING_NOTIFICATION_ID, notification)
}

/**
 * 마지막 주차 위치 알림을 명시적으로 제거한다.
 *
 * 상태바 알림 표시를 OFF로 바꿀 때는 Foreground Service를 멈추는 것과 별개로
 * 같은 알림 ID를 cancel해서, 서비스 밖에서 직접 notify된 알림까지 확실히 내린다.
 */
fun cancelLastParkingNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(PARKING_NOTIFICATION_ID)
}

/** 저장 시각(epoch millis)을 "3월 12일" 형식(날짜까지만) 문자열로 변환. */
private fun formatNotificationTime(millis: Long): String {
    val formatter = SimpleDateFormat("M월 d일", Locale.KOREAN)
    return formatter.format(Date(millis))
}
