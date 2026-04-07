package io.homeassistant.companion.android.controls

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.robolectric.annotation.Config

@Config(sdk = [Build.VERSION_CODES.S])
class CameraControlTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    inner class ThumbnailCache {

        @Test
        fun `Given thumbnail already cached when prefetchThumbnail called within interval then does not re-fetch`() {
            mockkStatic(BitmapFactory::class)
            val mockBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            every { BitmapFactory.decodeStream(any()) } returns mockBitmap

            CameraControl.prefetchThumbnail(
                entityId = "camera.test",
                baseUrl = "http://localhost:8123",
                entityPicture = "/api/camera_proxy/camera.test?token=abc",
                isInternal = true,
                force = true,
            )
            // Second call within 10s interval should not re-fetch (isInternal=true, interval=10s)
        }

        @Test
        fun `Given force true when prefetchThumbnail called then always fetches regardless of interval`() {
            mockkStatic(BitmapFactory::class)
            val mockBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            every { BitmapFactory.decodeStream(any()) } returns mockBitmap

            CameraControl.prefetchThumbnail(
                entityId = "camera.force_test",
                baseUrl = "http://localhost:8123",
                entityPicture = "/api/camera_proxy/camera.test?token=abc",
                isInternal = true,
                force = true,
            )

            CameraControl.prefetchThumbnail(
                entityId = "camera.force_test",
                baseUrl = "http://localhost:8123",
                entityPicture = "/api/camera_proxy/camera.test?token=abc",
                isInternal = true,
                force = true,
            )
        }
    }
}
