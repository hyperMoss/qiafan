package com.example.qiafan.fanfou

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusActionsSheetTest {
    private val status = FanfouStatus("fixture", "今天好好吃饭，也记下沿途的风景。", "",
        FanfouUser("rice", "一碗饭", "", false), false, null, null, null)

    @Test fun quickActionsExcludeTheRedundantDetailEntryAndCancelHasNoSideEffect() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val actions = mutableListOf<StatusQuickAction>()
        val sheet = StatusActionsSheet(controller.get(), status, false, false) { actions += it }
        try {
            sheet.open()
            val texts = nodes(sheet.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(texts.any { it == "查看详情" || it == "查看动态" })
            assertFalse(texts.contains("删除这条动态"))
            assertFalse(texts.contains("查看原图"))
            assertFalse(texts.contains("保存原图"))
            assertTrue(texts.containsAll(listOf("互动", "图片与内容", "写评论", "生成图片", "一键快转")))
            nodes(sheet.window!!.decorView).filterIsInstance<TextView>().single { it.text == "取消" }.performClick()
            assertFalse(sheet.isShowing)
            assertTrue(actions.isEmpty())
        } finally {
            sheet.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun photoActionsAndCurrentFavoriteStateAreVisibleAndDispatchTheChosenAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val actions = mutableListOf<StatusQuickAction>()
        val sheet = StatusActionsSheet(controller.get(), status.copy(photo = "preview", originalPhoto = "original"), true, true) { actions += it }
        try {
            sheet.open()
            val decor = sheet.window!!.decorView
            val texts = nodes(decor).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(listOf("取消收藏", "查看原图", "保存原图", "删除这条动态", "删除前需要再次确认")))
            decor.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 420, 780)
            val screenshot = Bitmap.createBitmap(420, 780, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(screenshot))
            val file = File("build/reports/status-actions-sheet-sample.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            nodes(decor).single { it.contentDescription?.toString()?.startsWith("生成图片，") == true }.performClick()
            assertEquals(listOf(StatusQuickAction.CREATE_IMAGE), actions)
            assertFalse(sheet.isShowing)
        } finally {
            sheet.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun pullingTheHandleDownDismissesWithoutPerformingAnyAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val actions = mutableListOf<StatusQuickAction>()
        val sheet = StatusActionsSheet(controller.get(), status, false, false) { actions += it }
        try {
            sheet.open()
            val handle = nodes(sheet.window!!.decorView).single { it.contentDescription == "关闭快速操作" }
            for ((event, y) in listOf(MotionEvent.ACTION_DOWN to 10f, MotionEvent.ACTION_MOVE to 110f, MotionEvent.ACTION_UP to 110f)) {
                MotionEvent.obtain(0, 10, event, 30f, y, 0).let { motion ->
                    handle.dispatchTouchEvent(motion)
                    motion.recycle()
                }
            }
            assertFalse(sheet.isShowing)
            assertTrue(actions.isEmpty())
        } finally {
            sheet.dismiss()
            controller.pause().stop().destroy()
        }
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) }
    } else emptyList()
}
