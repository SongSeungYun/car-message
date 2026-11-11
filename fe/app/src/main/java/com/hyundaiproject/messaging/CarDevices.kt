package com.hyundaiproject.messaging

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

private const val TAG = "CarMessage"
private const val PREFIX = "com.hyundaiproject.messaging."
private const val HAZARD = PREFIX + "HAZARD"
private const val PTT = PREFIX + "PTT"
private const val VOICE_RESULT = PREFIX + "VOICE_RESULT"

/**
 * 차량 신호를 앱 안 이벤트로 바꾼다. 신호가 어디서 오는지(Vehicle SDK·adb·맥 음성 브리지)는 여기만 안다.
 * 형식은 docs/device-signals.md. 콜백은 메인 스레드에서 온다.
 */
internal class CarSignals(
    private val context: Context,
    private val onTurnSignal: (String) -> Unit, // LEFT, RIGHT, OFF
    private val onHazard: (Boolean) -> Unit,
    private val onTalkButton: () -> Unit,       // 버튼 누름 = 듣기 시작
    private val onHeard: (String) -> Unit,      // 들은 말. 버튼을 떼면 PTT UP 없이 이것만 온다
) {
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            state?.let { handler.post { onTurnSignal(it.name) } }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "turn signal failed: ${e.message}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HAZARD -> onHazard(intent.getStringExtra("state") == "ON")
                PTT -> if (intent.getStringExtra("state") == "DOWN") onTalkButton()
                VOICE_RESULT -> onHeard(intent.getStringExtra("text").orEmpty())
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(HAZARD)
            addAction(PTT)
            addAction(VOICE_RESULT)
        }
        // adb 셸에서 오는 신호라 exported로 받는다
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        runCatching {
            vehicle = Vehicle(context.applicationContext).apply {
                initialize()
                getTurnSignal().registerTurnSignalState(turnListener)
            }
        }.onFailure { Log.w(TAG, "Vehicle SDK unavailable, use the demo menu: ${it.message}") } // Pleos가 아닌 기기
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        vehicle?.let {
            runCatching {
                it.getTurnSignal().unregisterTurnSignalState(turnListener)
                it.release()
            }
        }
    }
}

/** 받은 메시지를 맥 음성 브리지 /speak로 읽힌다. 실패해도 화면 표시는 그대로 둔다 */
internal class HostSpeaker(private val bridgeUrl: String, private val car: String) {
    private val client = OkHttpClient()

    fun speak(text: String) {
        val body = JSONObject().put("car", car).put("text", text).toString()
            .toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$bridgeUrl/speak").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "speak failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}
