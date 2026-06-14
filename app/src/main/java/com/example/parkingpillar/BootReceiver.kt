package com.example.parkingpillar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 기기 부팅 완료 방송(BOOT_COMPLETED)을 받아 상시 알림 서비스를 다시 시작하는 리시버.
 *
 * ── BroadcastReceiver란? ───────────────────────────────────────────────
 * 시스템이나 앱이 보내는 "방송(broadcast)"을 받는 컴포넌트.
 * 화면도 없고 계속 떠 있지도 않으며, 해당 방송이 올 때만 잠깐 깨어나
 * onReceive()가 실행된 뒤 곧 사라진다.
 *
 * BOOT_COMPLETED는 부팅이 끝나면 시스템이 모든 앱에 보내는 방송이다.
 * Manifest에 정적 등록(+ RECEIVE_BOOT_COMPLETED 권한)해 두면 앱이 꺼져 있어도 받는다.
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * 방송이 도착하면 호출된다. (메인 스레드에서 짧게 실행되어야 한다 — 무거운 작업 금지)
     */
    override fun onReceive(context: Context, intent: Intent) {
        // 혹시 다른 방송이 잘못 전달될 수 있으니, 부팅 완료 방송이 맞는지 먼저 확인한다.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // ── goAsync()로 비동기 작업 시간 벌기 ───────────────────────────
        // onReceive는 기본적으로 "메서드가 끝나면 끝"이라, 그 안에서 코루틴을 띄워
        // DataStore를 읽는 도중 시스템이 리시버를 회수해버릴 수 있다(읽기 미완료).
        // goAsync()는 "아직 처리 중이니 잠깐 기다려달라"는 PendingResult 토큰을 받아오는 것.
        // 비동기 작업이 끝나면 반드시 finish()를 호출해 "이제 끝났다"고 알려야 한다.
        val pendingResult = goAsync()

        // 짧은 IO 코루틴에서 알림 설정을 읽고, ON일 때만 서비스를 시작한다.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 사용자가 알림을 OFF로 둔 경우엔 부팅 후에도 서비스를 시작하지 않는다.
                if (notificationEnabledFlow(context.applicationContext).first()) {
                    // ── 부팅 후 전경 서비스 시작 제약 처리 ──────────────────────
                    // 안드로이드는 백그라운드 FGS 시작을 제한하지만, BOOT_COMPLETED 직후는
                    // 허용 예외다. 다만 배터리 세이버/제조사 정책 등으로 막히면 Android 12+에서
                    // ForegroundServiceStartNotAllowedException 등이 날 수 있어 try/catch로 감싼다.
                    // (못 떠도 사용자가 앱을 한 번 열면 onCreate에서 복원된다.)
                    ParkingService.start(context.applicationContext)
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "부팅 후 서비스 시작 실패(정책 제약 가능): ${e.message}")
            } finally {
                // 비동기 처리 완료를 시스템에 알린다 (안 부르면 ANR/누수 위험)
                pendingResult.finish()
            }
        }
    }
}
