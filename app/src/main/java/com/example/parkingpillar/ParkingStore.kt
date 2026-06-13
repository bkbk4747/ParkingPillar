package com.example.parkingpillar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ──────────────────────────────────────────────────────────────────────────
// Preferences DataStore 설정
//
// preferencesDataStore(name = ...)는 Context의 확장 프로퍼티로 DataStore를
// "앱당 단 하나"만 만들어 준다. (같은 name으로 두 번 만들면 크래시)
// 실제 파일은 앱 전용 내부 저장소에 저장된다:
//   /data/data/com.example.parkingpillar/files/datastore/parking_prefs.preferences_pb
// → 다른 앱 접근 불가, 앱을 껐다 켜도 유지, 앱 삭제 시 함께 제거.
// ──────────────────────────────────────────────────────────────────────────
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "parking_prefs")

// 저장할 키 — 딱 두 개만 사용 (마지막 위치 텍스트 / 저장 시각)
private val KEY_LOCATION_TEXT = stringPreferencesKey("location_text")
private val KEY_SAVED_AT_MILLIS = longPreferencesKey("saved_at_millis")

/** 마지막 주차 위치 한 건. 저장된 게 없으면 null로 표현한다. */
data class LastParking(
    val text: String,
    val savedAtMillis: Long,
)

/**
 * 마지막 주차 위치를 구독(읽기)한다.
 *
 * DataStore의 .data는 Flow<Preferences> 이다. 값이 바뀔 때마다 새 Preferences를
 * 흘려보내므로, 저장(saveLastParking)이 일어나면 이 Flow가 자동으로 최신 값을 방출한다.
 * → 화면에서 collectAsState로 구독하면 저장 즉시 UI가 갱신되고,
 *   앱을 다시 켜도 디스크에서 읽어와 그대로 복원된다.
 *
 * 위치 텍스트가 아직 없으면(처음 실행 등) null을 방출한다.
 */
fun lastParkingFlow(context: Context): Flow<LastParking?> =
    context.dataStore.data.map { prefs ->
        // prefs[키]는 값이 없으면 null을 반환한다.
        val text = prefs[KEY_LOCATION_TEXT]
        val savedAt = prefs[KEY_SAVED_AT_MILLIS]
        // 둘 다 있을 때만 유효한 기록으로 간주
        if (text != null && savedAt != null) {
            LastParking(text = text, savedAtMillis = savedAt)
        } else {
            null
        }
    }

/**
 * 마지막 주차 위치를 저장(쓰기)한다. 코루틴 안에서 호출해야 한다(suspend).
 *
 * edit { }는 현재 값을 받아 수정한 뒤 원자적으로 디스크에 커밋한다.
 * 항상 같은 두 키에 덮어쓰므로 과거 기록이 쌓이지 않고 "최신 하나"만 유지된다.
 *
 * @param text 저장할 위치 텍스트 (앞뒤 공백 제거 후 저장)
 */
suspend fun saveLastParking(context: Context, text: String): LastParking {
    val trimmedText = text.trim()
    val savedAtMillis = System.currentTimeMillis()

    context.dataStore.edit { prefs ->
        prefs[KEY_LOCATION_TEXT] = trimmedText
        // 저장 시각은 저장 시점의 현재 시간(epoch millis)
        prefs[KEY_SAVED_AT_MILLIS] = savedAtMillis
    }

    return LastParking(text = trimmedText, savedAtMillis = savedAtMillis)
}
