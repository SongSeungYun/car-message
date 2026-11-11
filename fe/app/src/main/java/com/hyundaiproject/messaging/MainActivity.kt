package com.hyundaiproject.messaging

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import com.hyundaiproject.messaging.MessageCatalog.Page
import com.hyundaiproject.messaging.MessageGateway.Incoming.Kind
import com.hyundaiproject.messaging.MessageGateway.Result

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: MessagingController
    private lateinit var gateway: DemoMessageGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = DemoMessageGateway(handler)
        val board = MessageBoard(this)
        controller = MessagingController(gateway, { delay, task ->
            handler.postDelayed(task, delay)
            MessageGateway.Cancellation { handler.removeCallbacks(task) }
        }, board::render)
        board.onSelect = controller::select
        board.onPage = controller::showPage
        board.onConfirm = controller::confirm
        board.onCancel = controller::cancelConfirmation
        board.onDismissAlert = controller::dismissAlert
        board.onDemo = ::showDemoMenu
        setContentView(board)
        board.render(controller.screen())
    }

    private fun showDemoMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add("Flow 1 · 비상등 작동 중").setOnMenuItemClickListener {
            restartDemo(Page.MAIN)
            true
        }
        menu.add("Flow 2 · 좌회전 방향지시등 작동 중").setOnMenuItemClickListener {
            restartDemo(Page.TURN)
            true
        }
        menu.add("Flow 3 · 경고 수신").setOnMenuItemClickListener {
            restartDemo(Page.MAIN)
            gateway.emit(Kind.CRITICAL)
            true
        }
        menu.add("Flow 4 · 음성 인식 시연").setOnMenuItemClickListener {
            restartDemo(Page.MAIN)
            controller.startListening()
            handler.postDelayed({ controller.recognize("1번 메시지 보내줘") }, 750)
            true
        }
        menu.add("Flow 5 · 다음 전송 결과 - 성공").setOnMenuItemClickListener { setDemoResult(Result.SUCCESS) }
        menu.add("Flow 6 · 다음 전송 결과 - 실패").setOnMenuItemClickListener { setDemoResult(Result.FAILURE) }
        menu.add("Flow 7 · mainpage").setOnMenuItemClickListener {
            restartDemo(Page.HOME)
            true
        }
        popup.show()
    }

    private fun restartDemo(startPage: Page) {
        handler.removeCallbacksAndMessages(null)
        controller.resetForDemo(startPage)
    }

    private fun setDemoResult(result: Result): Boolean {
        gateway.nextResult = result
        Toast.makeText(this, if (result == Result.SUCCESS) "다음 전송: 성공" else "다음 전송: 실패", Toast.LENGTH_SHORT).show()
        return true
    }

    @Deprecated("Used for in-app navigation on Android API 28 and above")
    override fun onBackPressed() {
        if (!controller.back()) super.onBackPressed()
    }

    override fun onDestroy() {
        controller.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
