package com.example.qiafan.fanfou

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PhotoQualityTest {
    @Test fun timelinesUseTheFullResolutionPhotoWhenTheApiAlsoProvidesSmallPreviews() {
        val api = FanfouApi { error("此测试不访问账号") }
        val payload = JSONObject("""{"photo":{
          "imageurl":"https://photo.fanfou.com/preview.jpg",
          "largeurl":"https://photo.fanfou.com/original.jpg"
        }}""")
        val parser = FanfouApi::class.java.getDeclaredMethod("statusFrom", JSONObject::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val status = parser.invoke(api, payload, false) as FanfouStatus
        assertEquals("列表不应放大小预览图", "https://photo.fanfou.com/original.jpg", status.photo)
        assertEquals("https://photo.fanfou.com/original.jpg", status.originalPhoto)
    }

    @Test fun missingOrUnsafeOriginalFallsBackToTheBestAvailableHttpsPreview() {
        val api = FanfouApi { error("此测试不访问账号") }
        val parser = FanfouApi::class.java.getDeclaredMethod("statusFrom", JSONObject::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        for (original in listOf("", "javascript:alert(1)")) {
            val photo = JSONObject().put("largeurl", original)
                .put("imageurl", "http://photo.fanfou.com/preview.jpg")
                .put("thumburl", "https://photo.fanfou.com/thumb.jpg")
            val status = parser.invoke(api, JSONObject().put("photo", photo), false) as FanfouStatus
            assertEquals("https://photo.fanfou.com/preview.jpg", status.photo)
            assertNull(status.originalPhoto)
        }
        val thumbnail = JSONObject().put("photo", JSONObject().put("thumburl", "https://photo.fanfou.com/thumb.jpg"))
        assertEquals("https://photo.fanfou.com/thumb.jpg", (parser.invoke(api, thumbnail, false) as FanfouStatus).photo)
    }

    @Test fun listAvatarsUseTheHighResolutionUrlWhenAvailable() {
        val api = FanfouApi { error("此测试不访问账号") }
        val parser = FanfouApi::class.java.getDeclaredMethod("statusFrom", JSONObject::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val payload = JSONObject("""{"user":{
          "profile_image_url":"https://avatar.fanfou.com/small.jpg",
          "profile_image_url_large":"https://avatar.fanfou.com/large.jpg"
        }}""")
        val status = parser.invoke(api, payload, false) as FanfouStatus
        assertEquals("https://avatar.fanfou.com/large.jpg", status.user.avatar)
    }
}
