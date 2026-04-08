package io.homeassistant.companion.android.controls

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ThumbnailTemplate
import androidx.annotation.RequiresApi
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.util.STATE_UNAVAILABLE
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

@RequiresApi(Build.VERSION_CODES.S)
object CameraControl : HaControl {

    /** In-memory cache of camera thumbnails by entity ID */
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()

    /** Timestamp of last fetch per entity ID */
    private val lastFetchTime = ConcurrentHashMap<String, Long>()

    /**
     * Pre-fetch a camera thumbnail in background. Called by WebsocketManager.
     * @param isInternal true if on local network (refresh more often)
     */
    fun prefetchThumbnail(
        entityId: String,
        baseUrl: String,
        entityPicture: String,
        isInternal: Boolean,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force) {
            val interval = if (isInternal) 10_000L else 600_000L // 10s local, 10min external
            val lastFetch = lastFetchTime[entityId] ?: 0L
            if (now - lastFetch < interval) return
        }
        lastFetchTime[entityId] = now
        try {
            val connection = URL(baseUrl + entityPicture).openConnection().apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val bitmap = BitmapFactory.decodeStream(connection.getInputStream())
            if (bitmap != null) {
                thumbnailCache[entityId] = bitmap
            }
        } catch (e: Exception) {
            Timber.e(e, "Couldn't prefetch thumbnail for $entityId")
        }
    }

    override fun provideControlFeatures(
        context: Context,
        control: Control.StatefulBuilder,
        entity: Entity,
        info: HaControlInfo,
    ): Control.StatefulBuilder {
        // Only use cached thumbnails here. provideControlFeatures can run on the main thread
        // (via sendCachedControlsImmediately) and any blocking network call would trip StrictMode.
        // Thumbnails are populated asynchronously by prefetchThumbnail (background sync via
        // WebsocketManager, and on-demand via HaControlsProviderService.refreshCameraThumbnails).
        val image = thumbnailCache[entity.entityId]
        val icon = if (image != null) {
            Icon.createWithBitmap(image)
        } else {
            Icon.createWithResource(context, R.drawable.control_camera_placeholder)
        }
        control.setControlTemplate(
            ThumbnailTemplate(
                entity.entityId,
                entity.state != STATE_UNAVAILABLE && image != null,
                icon,
                context.getString(commonR.string.widget_camera_contentdescription),
            ),
        )
        return control
    }

    override fun getDeviceType(entity: Entity): Int = DeviceTypes.TYPE_CAMERA

    override fun getDomainString(context: Context, entity: Entity): String =
        context.getString(commonR.string.domain_camera)

    override suspend fun performAction(integrationRepository: IntegrationRepository, action: ControlAction): Boolean {
        // No action is received, Android immediately invokes long press
        return true
    }
}
