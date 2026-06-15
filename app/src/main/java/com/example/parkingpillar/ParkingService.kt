package com.example.parkingpillar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 마지막 주차 위치를 상태바에 "상시" 표시하는 Foreground Service.
 *
 * ── Service란? (Activity와 무엇이 다른가) ───────────────────────────────
 * Activity는 사용자가 보는 "화면"이라 앱을 닫으면 함께 사라진다.
 * Service는 화면이 없는 백그라운드 컴포넌트라, 앱(Activity)이 닫혀도 계속 살 수 있다.
 *
 * ── Foreground Service ────────────────────────────────────────────────
 * "사용자가 인지하는 작업"을 하는 서비스로, 반드시 상태바 알림을 동반해야 한다.
 * 그 알림이 곧 우리 주차 알림이며, 대가로 시스템이 잘 종료시키지 않아
 * 앱을 꺼도 알림이 유지된다.
 */
class ParkingService : Service() {

    // 서비스 전용 코루틴 스코프. DataStore Flow를 백그라운드(IO)에서 구독하기 위함.
    // SupervisorJob: 자식 코루틴 하나가 실패해도 스코프 전체가 죽지 않게 한다.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── isCollecting 가드 ───────────────────────────────────────────────
    // onStartCommand는 서비스가 "시작될 때마다" 호출된다. 우리는 앱 실행, 부팅 복원,
    // 그리고 이번에 추가하는 "알림 지움 → 재게시(REPOST)"까지 여러 경로로 이 함수를
    // 다시 부른다. 그때마다 Flow를 새로 구독(launch)하면 구독이 중첩되어
    // 같은 알림 갱신이 2배, 3배로 쌓인다(누수/중복 발송). 그래서 "구독은 딱 한 번만"
    // 하도록 이 플래그로 막는다. 첫 호출에만 true로 바꾸고 구독을 시작한다.
    private var isCollecting = false

    /**
     * onStartCommand: start(context)로 서비스가 시작될 때마다 호출된다(생명주기 시작점).
     *
     * 핵심 규칙: 서비스가 시작되면 약 3초 이내에 반드시 startForeground()를 호출해
     * "나는 전경 서비스다"라고 알려야 한다. 안 그러면 시스템이 ANR/예외로 강제 종료한다.
     *
     * 또한 알림이 스와이프로 지워지면 deleteIntent가 ACTION_REPOST 액션으로 이 서비스를
     * 다시 깨운다. 액션과 무관하게 매번 startForeground를 호출하므로, 그 순간 알림이
     * 상태바에 다시 붙는다(= 지워도 다시 뜸).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 0) 명시적 정지 요청(사용자가 토글 OFF): 알림 제거 후 서비스 종료.
        //    stopForeground(REMOVE)로 알림을 확실히 내리고 stopSelf로 서비스를 끝낸다.
        //    START_NOT_STICKY를 반환해 시스템이 다시 살리지 않게 한다. (위치 데이터는 안 건드림)
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            cancelLastParkingNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // 1) 알림 게시.
        //    - 일반 시작/부팅: 5초 규칙 때문에 "즉시" startForeground 해야 한다.
        //    - REPOST(사용자가 지운 직후): 곧바로 다시 띄우면 거슬리므로 3초 뒤 재게시한다.
        //      이 시점엔 서비스가 이미 살아있는 상태라 위 3초 규칙에 걸리지 않는다.
        if (intent?.action == ACTION_REPOST) {
            serviceScope.launch {
                delay(3_000)  // 지운 뒤 3초 텀
                // ── 재게시 가드: 사용자가 알림을 OFF로 꺼둔 상태면 다시 띄우지 않는다 ──
                // deleteIntent가 발사돼도, 설정이 OFF면 부활시키지 않고 서비스를 끝낸다.
                // (이게 없으면 OFF로 꺼도 재게시로 알림이 도로 살아난다)
                if (!notificationEnabledFlow(applicationContext).first()) {
                    withContext(Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@launch
                }

                // ── 재게시 때 "위치 없음"으로 뜨던 버그 수정 ──────────────────
                // 재게시는 데이터가 바뀐 게 아니라 알림만 지워진 상황이라, Flow 구독이
                // 새 값을 방출하지 않는다. 그래서 null로 빌드하면 "저장된 위치 없음"으로
                // 굳어버렸다. 여기서 first()로 DataStore의 현재 값을 직접 읽어와
                // 그 최신 위치로 알림을 만든다. (first = Flow의 첫 방출 1건을 기다려 받음)
                val last = lastParkingFlow(applicationContext).first()
                // startForeground는 메인 스레드에서 호출 (UI/시스템 상호작용 안전)
                withContext(Dispatchers.Main) {
                    startForeground(
                        PARKING_NOTIFICATION_ID,
                        buildParkingNotification(this@ParkingService, last)
                    )
                }
            }
        } else {
            startForeground(PARKING_NOTIFICATION_ID, buildParkingNotification(this, null))
        }

        // 2) DataStore의 마지막 위치를 "한 번만" 구독 → 값이 바뀔 때마다 알림을 갱신.
        //    collectLatest: 새 값이 오면 이전 처리를 취소하고 최신 값으로 갱신.
        //    저장(saveLastParking)이 일어나면 Flow가 새 값을 방출 → 알림 자동 최신화.
        //    (위 가드로 REPOST/재시작 때 중복 구독을 방지한다)
        if (!isCollecting) {
            isCollecting = true
            serviceScope.launch {
                lastParkingFlow(applicationContext).collectLatest { last ->
                    // 알림 OFF 상태에서는 서비스가 아직 완전히 종료되기 전이라도
                    // 위치 Flow 갱신이 알림을 다시 살리면 안 된다.
                    if (notificationEnabledFlow(applicationContext).first()) {
                        showLastParkingNotification(applicationContext, last)
                    }
                }
            }
        }

        // 3) START_STICKY: 시스템이 메모리 부족으로 서비스를 죽여도, 여유가 생기면
        //    서비스를 다시 만들어(onStartCommand 재호출) 알림을 복구한다.
        return START_STICKY
    }

    /**
     * onBind: 다른 컴포넌트가 이 서비스에 "바인딩"(직접 호출 연결)하려 할 때 쓰인다.
     * 우리는 바인딩이 필요 없는 "시작형 서비스"이므로 null을 반환한다.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * onDestroy: 서비스가 종료될 때 호출되는 생명주기 끝점.
     * 구독 중이던 코루틴을 정리해 누수를 막는다.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        // 알림이 지워졌을 때 deleteIntent가 이 액션으로 서비스를 다시 깨운다(재게시 신호).
        const val ACTION_REPOST = "com.example.parkingpillar.REPOST_NOTIFICATION"
        // 사용자가 토글 OFF로 끌 때 서비스에 보내는 정지 신호.
        const val ACTION_STOP = "com.example.parkingpillar.STOP_SERVICE"

        /** 서비스를 전경 서비스로 시작한다. (Android 8+는 startForegroundService 사용) */
        fun start(context: Context) {
            if (!canShowParkingNotification(context)) return
            val intent = Intent(context, ParkingService::class.java)
            // startForegroundService로 시작하면, 서비스는 5초 내 startForeground를 호출해야 한다
            context.startForegroundService(intent)
        }

        /**
         * 서비스를 멈춰 상태바 알림을 제거한다. (사용자가 알림을 OFF로 끌 때 사용)
         *
         * 중요: 이건 "알림 표시"만 멈추는 것이다. DataStore의 위치 데이터는 전혀
         * 건드리지 않으므로, 다시 start()하면 저장돼 있던 마지막 위치로 알림이 복원된다.
         */
        fun stop(context: Context) {
            // ACTION_STOP을 onStartCommand로 보내, 거기서 stopForeground+stopSelf로 확실히 종료.
            // (단순 stopService보다 결정적이고, 재게시/지연 코루틴 부활을 막기 좋다)
            cancelLastParkingNotification(context)
            val intent = Intent(context, ParkingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
