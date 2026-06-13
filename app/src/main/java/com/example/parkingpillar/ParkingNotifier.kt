package com.example.parkingpillar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 알림 채널 ID(채널 식별자)와 알림 ID(이 알림 1건의 식별자)
private const val CHANNEL_ID = "parking_location"
private const val NOTIFICATION_ID = 1001

// 알림의 "말하기"로 앱을 열었음을 알리는 Intent extra 키.
// MainActivity가 이 키를 읽어 true면 음성 인식을 자동 시작한다.
const val EXTRA_AUTO_START_VOICE = "com.example.parkingpillar.AUTO_START_VOICE"

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
 * DataStore에 저장된 마지막 주차 위치를 담아 알림을 한 번 띄운다.
 *
 * @param last 저장된 마지막 위치(없으면 null)
 *
 * 주의: Android 13+에서는 POST_NOTIFICATIONS 권한이 있어야 실제로 표시된다.
 * 권한이 없으면 notify() 호출은 조용히 무시되므로, 호출 측에서 권한을 먼저 확인한다.
 */
fun showLastParkingNotification(context: Context, last: LastParking?) {
    // 1) 채널 보장 — 알림 발송 전 반드시 채널이 존재해야 한다
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
        0,                       // requestCode: 여러 PendingIntent를 구분하는 번호 (지금은 1개라 0)
        openAppIntent,
        // ── FLAG는 왜 붙이나? ──────────────────────────────────────────
        // FLAG_IMMUTABLE: 위임받은 시스템이 이 Intent의 내용을 못 바꾸게 잠근다.
        //   Android 12(API 31)+ 부터는 보안상 IMMUTABLE / MUTABLE 중 하나를 반드시 명시해야 한다.
        // FLAG_UPDATE_CURRENT: 같은 PendingIntent가 이미 있으면 새 내용으로 갱신한다.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // 4) 알림 빌드 — NotificationCompat은 구버전 호환을 알아서 처리해준다
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)      // 상태바 작은 아이콘 (기존 런처 아이콘 재사용)
        .setContentTitle("🚗 내차기둥")
        .setContentText(contentText)
        // 본문이 길어도 펼쳐 볼 수 있게 BigTextStyle 적용
        .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // 구버전(채널 없는 기기)용 우선순위
        .setAutoCancel(true)                      // 탭하면 알림이 사라지게
        // "말하기" 액션 버튼 — 아이콘(임시: 기본 마이크) + 라벨 + 위 PendingIntent
        .addAction(
            android.R.drawable.ic_btn_speak_now,
            "말하기",
            speakPendingIntent
        )
        .build()

    // 4) 발송 — NOTIFICATION_ID가 같으면 새 알림이 쌓이지 않고 기존 알림을 갱신한다
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
}

/** 저장 시각(epoch millis)을 "3월 12일" 형식(날짜까지만) 문자열로 변환. */
private fun formatNotificationTime(millis: Long): String {
    val formatter = SimpleDateFormat("M월 d일", Locale.KOREAN)
    return formatter.format(Date(millis))
}
