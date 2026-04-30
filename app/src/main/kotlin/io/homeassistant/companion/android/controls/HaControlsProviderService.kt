package io.homeassistant.companion.android.controls

import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.actions.ControlAction
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.data.integration.ControlsAuthRequiredSetting
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.CAMERA_DOMAIN
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.CLIMATE_DOMAIN
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.MEDIA_PLAYER_DOMAIN
import io.homeassistant.companion.android.common.data.integration.applyCompressedStateDiff
import io.homeassistant.companion.android.common.data.network.NetworkHelper
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.firstUrlOrNull
import io.homeassistant.companion.android.common.data.websocket.WebSocketRepository
import io.homeassistant.companion.android.common.data.websocket.WebSocketState
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.DeviceRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.EntityRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.isLiveConnectionTrustworthy
import io.homeassistant.companion.android.util.RegistriesDataHandler
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber

@RequiresApi(Build.VERSION_CODES.R)
@AndroidEntryPoint
class HaControlsProviderService : ControlsProviderService() {

    companion object {
        private val domainToHaControl = mapOf(
            "automation" to DefaultSwitchControl,
            "button" to DefaultButtonControl,
            CAMERA_DOMAIN to CameraControl,
            CLIMATE_DOMAIN to ClimateControl,
            "cover" to CoverControl,
            "fan" to FanControl,
            "ha_failed" to HaFailedControl,
            "humidifier" to DefaultSwitchControl,
            "input_boolean" to DefaultSwitchControl,
            "input_button" to DefaultButtonControl,
            "input_number" to DefaultSliderControl,
            "light" to LightControl,
            "lock" to LockControl,
            MEDIA_PLAYER_DOMAIN to MediaPlayerControl,
            "number" to DefaultSliderControl,
            "remote" to DefaultSwitchControl,
            "scene" to DefaultButtonControl,
            "script" to DefaultButtonControl,
            "siren" to DefaultSwitchControl,
            "switch" to DefaultSwitchControl,
            "vacuum" to VacuumControl,
        )
        private val domainToMinimumApi = mapOf(
            CAMERA_DOMAIN to Build.VERSION_CODES.S,
        )

        fun getSupportedDomains(): List<String> = domainToHaControl
            .map { it.key }
            .filter {
                domainToMinimumApi[it] == null ||
                    Build.VERSION.SDK_INT >= domainToMinimumApi[it]!!
            }

        /** Cache of last known entity states, survives service recreation. Outer key: serverId, inner key: entityId */
        internal val cachedEntities = ConcurrentHashMap<Int, ConcurrentHashMap<String, Entity>>()

        /** Last known control IDs per server, used by WebsocketManager to keep entities synced */
        internal val lastControlEntityIds = ConcurrentHashMap<Int, List<String>>()

        /** Last resolved base URL per server, used for instant cached control rendering */
        @Volatile
        internal var lastBaseUrl = ConcurrentHashMap<Int, String>()

        /**
         * Cached [WebSocketRepository] per server so the main thread can synchronously read the
         * current [WebSocketState] in `sendCachedControlsImmediately` to decide whether the
         * cached entity states are still trustworthy. Populated from suspend contexts that
         * already hold a repository reference (`subscribeToEntitiesForServer` and
         * `WebsocketManager.syncControlEntities`). The repository is a stable per-server
         * singleton retained by `ServerManager`, so reading `getConnectionState()` on it from
         * any thread is safe.
         */
        internal val webSocketRepositoryCache = ConcurrentHashMap<Int, WebSocketRepository>()
    }

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var prefsRepository: PrefsRepository

    @Inject
    lateinit var networkHelper: NetworkHelper

    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var areaRegistry = mutableMapOf<Int, List<AreaRegistryResponse>?>()
    private var deviceRegistry = mutableMapOf<Int, List<DeviceRegistryResponse>?>()
    private var entityRegistry = mutableMapOf<Int, List<EntityRegistryResponse>?>()

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        return Flow.Publisher { subscriber ->
            ioScope.launch {
                if (!serverManager.isRegistered()) {
                    subscriber.onComplete()
                    return@launch
                }

                val entities = mutableMapOf<Int, List<Entity>?>()
                val areaForEntity = mutableMapOf<Int, Map<String, AreaRegistryResponse?>>()

                val splitServersIntoMultipleStructures = splitMultiServersIntoStructures()

                serverManager.servers().map { server ->
                    async {
                        try {
                            val getAreaRegistry =
                                async { serverManager.webSocketRepository(server.id).getAreaRegistry() }
                            val getDeviceRegistry =
                                async { serverManager.webSocketRepository(server.id).getDeviceRegistry() }
                            val getEntityRegistry =
                                async { serverManager.webSocketRepository(server.id).getEntityRegistry() }
                            val getEntities = async { serverManager.integrationRepository(server.id).getEntities() }

                            areaRegistry[server.id] = getAreaRegistry.await()
                            deviceRegistry[server.id] = getDeviceRegistry.await()
                            entityRegistry[server.id] = getEntityRegistry.await()
                            entities[server.id] = getEntities.await()

                            areaForEntity[server.id] = entities[server.id].orEmpty().associate {
                                it.entityId to RegistriesDataHandler.getAreaForEntity(
                                    it.entityId,
                                    areaRegistry[server.id],
                                    deviceRegistry[server.id],
                                    entityRegistry[server.id],
                                )
                            }
                            entities[server.id] = entities[server.id].orEmpty()
                                .sortedWith(compareBy(nullsLast()) { areaForEntity[server.id]?.get(it.entityId)?.name })
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "Unable to load entities/registries for server ${server.id} (${server.friendlyName}), skipping",
                            )
                        }
                    }
                }.awaitAll()

                try {
                    val allEntities = mutableListOf<Pair<Int, Entity>>()
                    entities.forEach { serverEntities ->
                        serverEntities.value?.forEach { allEntities += Pair(serverEntities.key, it) }
                    }
                    val serverNames = mutableMapOf<Int, String>()
                    val servers = serverManager.servers()
                    if (servers.size > 1) {
                        servers.forEach { serverNames[it.id] = it.friendlyName }
                    }
                    allEntities
                        .filter {
                            domainToMinimumApi[it.second.domain] == null ||
                                Build.VERSION.SDK_INT >= domainToMinimumApi[it.second.domain]!!
                        }
                        .mapNotNull { (serverId, entity) ->
                            try {
                                val info = HaControlInfo(
                                    systemId = "$serverId.${entity.entityId}",
                                    entityId = entity.entityId,
                                    serverId = serverId,
                                    serverName = serverNames[serverId],
                                    area = getAreaForEntity(entity.entityId, serverId),
                                    splitMultiServerIntoStructure = splitServersIntoMultipleStructures,
                                ) // No auth for preview, no base url to prevent downloading images
                                domainToHaControl[entity.domain]?.createControl(
                                    applicationContext,
                                    entity,
                                    info,
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "Unable to create control for ${entity.domain} entity, skipping")
                                null
                            }
                        }
                        .forEach {
                            subscriber.onNext(it)
                        }
                } catch (e: Exception) {
                    Timber.e(e, "Error building list of entities")
                }
                subscriber.onComplete()
            }
        }
    }

    override fun createPublisherFor(controlIds: MutableList<String>): Flow.Publisher<Control> {
        Timber.d("publisherFor $controlIds")
        return Flow.Publisher { subscriber ->
            subscriber.onSubscribe(object : Flow.Subscription {
                val webSocketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                override fun request(n: Long) {
                    // Send cached controls IMMEDIATELY (synchronous, no coroutine dispatch)
                    sendCachedControlsImmediately(controlIds, subscriber)

                    // Force camera thumbnail refresh in background, then re-send camera controls
                    ioScope.launch {
                        refreshCameraThumbnails(controlIds, subscriber)
                    }

                    // Then launch async work for live updates
                    ioScope.launch {
                        try {
                            if (!serverManager.isRegistered()) {
                                Timber.w("request $n: not registered, aborting")
                                return@launch
                            }

                            // Resolve fallback serverId once instead of inside the groupBy lambda,
                            // which would otherwise hit Room O(controlIds) times per panel open.
                            val fallbackServerId = serverManager.servers().firstOrNull()?.id
                            controlIds
                                .groupBy {
                                    it.split(".")[0].toIntOrNull() ?: fallbackServerId
                                }.forEach { (serverId, serverControlIds) ->
                                    if (serverId == null) return@forEach
                                    subscribeToEntitiesForServer(
                                        serverId,
                                        serverControlIds,
                                        webSocketScope,
                                        subscriber,
                                    )
                                }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to set up live device controls")
                        }
                    }
                }

                override fun cancel() {
                    Timber.d("cancel")
                    webSocketScope.cancel()
                }
            })
        }
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        ioScope.launch {
            Timber.d("Control: $controlId, action: $action")
            if (!serverManager.isRegistered()) return@launch consumer.accept(ControlAction.RESPONSE_FAIL)

            var server = 0
            var domain = ""
            controlId.split(".")[0].toIntOrNull()?.let {
                server = it
                domain = controlId.split(".")[1]
            } ?: run {
                server = serverManager.servers().firstOrNull()!!.id
                domain = controlId.split(".")[0]
            }
            val haControl = domainToHaControl[domain]
            var actionSuccess = false
            if (haControl != null) {
                try {
                    actionSuccess = haControl.performAction(serverManager.integrationRepository(server), action)
                } catch (e: Exception) {
                    Timber.e(e, "Unable to control or get entity information")
                }
            }

            withContext(Dispatchers.Main) {
                if (actionSuccess) {
                    consumer.accept(ControlAction.RESPONSE_OK)
                } else {
                    consumer.accept(ControlAction.RESPONSE_UNKNOWN)
                }
            }
        }
    }

    private suspend fun subscribeToEntitiesForServer(
        serverId: Int,
        controlIds: List<String>,
        webSocketScope: CoroutineScope,
        subscriber: Flow.Subscriber<in Control>,
    ) {
        val serverCount = serverManager.servers().size
        val server = serverManager.getServer(serverId)

        // Server name should only be specified if there's more than one server, as controls being split by structure (or the area names appended with the server name)
        // is done based on the presence of a server name.
        var serverName: String? = null
        if (server != null && serverCount > 1) {
            serverName = server.friendlyName
        }

        val splitMultiServersIntoStructures = splitMultiServersIntoStructures()

        if (server == null) {
            controlIds.forEach {
                val entityId =
                    if (it.split(".")[0].toIntOrNull() != null) {
                        it.removePrefix("$serverId.")
                    } else {
                        it
                    }
                val entity = getFailedEntity(entityId, Exception())
                domainToHaControl["ha_failed"]?.createControl(
                    applicationContext,
                    entity,
                    HaControlInfo(
                        systemId = it,
                        entityId = entityId,
                        serverId = serverId,
                        area = getAreaForEntity(entity.entityId, serverId),
                    ),
                )?.let { control -> subscriber.onNext(control) }
            }
            return
        }

        // Resolve the repository once and cache it so the main thread can synchronously read
        // WebSocketState in `sendCachedControlsImmediately` later. Reusing the same reference
        // across the three registry fetches also avoids three extra Mutex acquisitions on
        // ServerMap.getOrCreate inside ServerManager.
        val webSocketRepository = serverManager.webSocketRepository(serverId)
        webSocketRepositoryCache[serverId] = webSocketRepository

        // Resolve all initial data in parallel (registries are cached, baseUrl is fast)
        val getAreaRegistry = ioScope.async { webSocketRepository.getAreaRegistry() }
        val getDeviceRegistry = ioScope.async { webSocketRepository.getDeviceRegistry() }
        val getEntityRegistry = ioScope.async { webSocketRepository.getEntityRegistry() }
        val getBaseUrl = ioScope.async {
            serverManager.connectionStateProvider(serverId).urlFlow().firstUrlOrNull()?.toString()?.removeSuffix("/")
                ?: ""
        }
        val entityIds = controlIds.map {
            if (it.split(".")[0].toIntOrNull() != null) {
                it.removePrefix("$serverId.")
            } else {
                it
            }
        }
        // Save entity IDs so WebsocketManager can keep them synced in background
        lastControlEntityIds[serverId] = entityIds
        ioScope.launch {
            prefsRepository.setControlsEntityIds(serverId, entityIds)
        }

        areaRegistry[serverId] = getAreaRegistry.await()
        deviceRegistry[serverId] = getDeviceRegistry.await()
        entityRegistry[serverId] = getEntityRegistry.await()
        val baseUrl = getBaseUrl.await()
        lastBaseUrl[serverId] = baseUrl

        // Cached controls were already sent synchronously in request().
        // Populate entities map from cache so live updates can apply diffs correctly.
        val entities = mutableMapOf<String, Entity>()
        val serverCache = cachedEntities[serverId]
        entityIds.forEach { entityId ->
            serverCache?.get(entityId)?.let { entities[entityId] = it }
        }

        val versionOk = try {
            serverManager.integrationRepository(serverId).isHomeAssistantVersionAtLeast(2022, 4, 0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to check HA version, falling back to legacy path")
            false
        }
        if (versionOk) {
            webSocketScope.launch {
                var sentInitial = false
                val error404 = HttpException(Response.error<ResponseBody>(404, byteArrayOf().toResponseBody()))

                try {
                    val flow = serverManager.webSocketRepository(serverId).getCompressedStateAndChanges(entityIds)
                    flow?.collect { event ->
                        val toSend = mutableMapOf<String, Entity>()
                        event.added?.forEach {
                            val entity = it.value.toEntity(it.key)
                            entities.remove("ha_failed.${it.key}")
                            entities[it.key] = entity
                            toSend[it.key] = entity
                        }
                        event.changed?.forEach {
                            val entity = entities[it.key]?.applyCompressedStateDiff(it.value)
                            entity?.let { thisEntity ->
                                entities[it.key] = thisEntity
                                toSend[it.key] = entity
                            }
                        }
                        event.removed?.forEach {
                            entities.remove(it)
                            val entity = getFailedEntity(it, error404)
                            entities["ha_failed.$it"] = entity
                            toSend["ha_failed.$it"] = entity
                        }
                        if (!sentInitial) {
                            // All initial states will be in the first message
                            sentInitial = true
                            (entityIds - entities.keys).forEach { missingEntity ->
                                Timber.e(
                                    "Unable to get $missingEntity from Home Assistant, not returned in subscribe_entities.",
                                )
                                val entity = getFailedEntity(missingEntity, error404)
                                entities["ha_failed.$missingEntity"] = entity
                                toSend["ha_failed.$missingEntity"] = entity
                            }
                        }
                        // Update static cache for instant display on next open
                        toSend.forEach { (key, entity) ->
                            if (!key.startsWith("ha_failed.")) {
                                cachedEntities.getOrPut(serverId) { ConcurrentHashMap() }[entity.entityId] = entity
                            }
                        }
                        // Always re-send ALL entities so Android doesn't mark unchanged ones as stale
                        Timber.d("Sending all ${entities.size} entities to subscriber (${toSend.size} changed)")
                        sendEntitiesToSubscriber(
                            subscriber,
                            controlIds,
                            entities,
                            serverId,
                            serverName,
                            webSocketScope,
                            baseUrl,
                        )
                    } ?: run {
                        entityIds.forEachIndexed { index, entityId ->
                            val entity = getFailedEntity(entityId, Exception())
                            entities["ha_failed.$entityId"] = entity
                            domainToHaControl["ha_failed"]?.createControl(
                                applicationContext,
                                entity,
                                HaControlInfo(
                                    systemId = controlIds[index],
                                    entityId = entity.entityId,
                                    serverId = serverId,
                                    area = getAreaForEntity(entity.entityId, serverId),
                                    authRequired = entityRequiresAuth(entity.entityId, serverId),
                                    baseUrl = baseUrl,
                                    serverName = serverName,
                                    splitMultiServerIntoStructure = splitMultiServersIntoStructures,
                                ),
                            )?.let { control -> subscriber.onNext(control) }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Exception in compressed state collect")
                }
            }
        } else {
            // Set up initial states
            entityIds.forEachIndexed { index, entityId ->
                webSocketScope.launch {
                    // using launch to create controls async
                    var id = entityId
                    try {
                        val entity = serverManager.integrationRepository(serverId).getEntity(entityId)
                        if (entity != null) {
                            entities[entityId] = entity
                        } else {
                            Timber.e("Unable to get $entityId from Home Assistant, null response.")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Unable to get $entityId from Home Assistant, caught exception.")
                        entities["ha_failed.$entityId"] = getFailedEntity(entityId, e)
                        id = "ha_failed.$entityId"
                    }
                    entities[id]?.let { entity ->
                        domainToHaControl[id.split(".")[0]]?.createControl(
                            applicationContext,
                            entity,
                            HaControlInfo(
                                systemId = controlIds[index],
                                entityId = entity.entityId,
                                serverId = serverId,
                                area = getAreaForEntity(entity.entityId, serverId),
                                authRequired = entityRequiresAuth(entity.entityId, serverId),
                                baseUrl = baseUrl,
                                serverName = serverName,
                                splitMultiServerIntoStructure = splitMultiServersIntoStructures,
                            ),
                        )?.let { control -> subscriber.onNext(control) }
                    }
                }
            }

            // Listen for the state changed events.
            webSocketScope.launch {
                serverManager.integrationRepository(serverId).getEntityUpdates(entityIds)?.collect {
                    val control = domainToHaControl[it.domain]?.createControl(
                        applicationContext,
                        it,
                        HaControlInfo(
                            systemId = controlIds[entityIds.indexOf(it.entityId)],
                            entityId = it.entityId,
                            serverId = serverId,
                            area = getAreaForEntity(it.entityId, serverId),
                            authRequired = entityRequiresAuth(it.entityId, serverId),
                            baseUrl = baseUrl,
                            serverName = serverName,
                            splitMultiServerIntoStructure = splitMultiServersIntoStructures,
                        ),
                    )
                    if (control != null) {
                        subscriber.onNext(control)
                    }
                }
            }
        }
        webSocketScope.launch {
            serverManager.webSocketRepository(serverId).getAreaRegistryUpdates()?.collect {
                areaRegistry[serverId] = serverManager.webSocketRepository(serverId).getAreaRegistry()
                sendEntitiesToSubscriber(
                    subscriber,
                    controlIds,
                    entities,
                    serverId,
                    serverName,
                    webSocketScope,
                    baseUrl,
                )
            }
        }
        webSocketScope.launch {
            serverManager.webSocketRepository(serverId).getDeviceRegistryUpdates()?.collect {
                deviceRegistry[serverId] = serverManager.webSocketRepository(serverId).getDeviceRegistry()
                sendEntitiesToSubscriber(
                    subscriber,
                    controlIds,
                    entities,
                    serverId,
                    serverName,
                    webSocketScope,
                    baseUrl,
                )
            }
        }
        webSocketScope.launch {
            serverManager.webSocketRepository(serverId).getEntityRegistryUpdates()?.collect { event ->
                if (event.action == "update" && entityIds.contains(event.entityId)) {
                    entityRegistry[serverId] = serverManager.webSocketRepository(serverId).getEntityRegistry()
                    sendEntitiesToSubscriber(
                        subscriber,
                        controlIds,
                        entities,
                        serverId,
                        serverName,
                        webSocketScope,
                        baseUrl,
                    )
                }
            }
        }
    }

    private suspend fun sendEntitiesToSubscriber(
        subscriber: Flow.Subscriber<in Control>,
        controlIds: List<String>,
        entities: Map<String, Entity>,
        serverId: Int,
        serverName: String?,
        coroutineScope: CoroutineScope,
        baseUrl: String,
    ) {
        val entityIds = controlIds.map {
            if (it.split(".")[0].toIntOrNull() != null) {
                it.removePrefix("$serverId.")
            } else {
                it
            }
        }
        val splitMultiServersIntoStructures = splitMultiServersIntoStructures()
        Timber.d("sendEntitiesToSubscriber: sending ${entities.size} entities, controlIds=${controlIds.size}")
        entities.forEach {
            val idx = entityIds.indexOf(it.value.entityId)
            if (idx == -1) {
                Timber.e("Entity ${it.value.entityId} not found in entityIds, skipping")
                return@forEach
            }
            try {
                val info = HaControlInfo(
                    systemId = controlIds[idx],
                    entityId = it.value.entityId,
                    serverId = serverId,
                    serverName = serverName,
                    area = getAreaForEntity(it.value.entityId, serverId),
                    authRequired = entityRequiresAuth(it.value.entityId, serverId),
                    baseUrl = baseUrl,
                    splitMultiServerIntoStructure = splitMultiServersIntoStructures,
                )
                val control = try {
                    domainToHaControl[it.key.split(".")[0]]?.createControl(
                        applicationContext,
                        it.value,
                        info,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Unable to create control for ${it.value.domain} entity, sending error entity")
                    domainToHaControl["ha_failed"]?.createControl(
                        applicationContext,
                        getFailedEntity(it.value.entityId, e),
                        info,
                    )
                }
                if (control != null) {
                    subscriber.onNext(control)
                }
            } catch (e: Exception) {
                Timber.e(e, "sendEntitiesToSubscriber: failed for entity ${it.value.entityId}")
            }
        }
    }

    /**
     * Force-refresh camera thumbnails for the given control IDs and re-send the updated controls
     * to the subscriber with fresh images.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun refreshCameraThumbnails(
        controlIds: MutableList<String>,
        subscriber: Flow.Subscriber<in Control>,
    ) {
        controlIds.forEach { controlId ->
            try {
                val serverId = controlId.split(".")[0].toIntOrNull()
                    ?: lastControlEntityIds.keys.firstOrNull() ?: return@forEach
                val entityId = controlId.removePrefix("$serverId.")
                if (!entityId.startsWith("camera.")) return@forEach
                val cachedEntity = cachedEntities[serverId]?.get(entityId) ?: return@forEach
                val baseUrl = lastBaseUrl[serverId] ?: return@forEach
                val entityPicture = cachedEntity.attributes["entity_picture"] as? String ?: return@forEach

                // Force refresh
                CameraControl.prefetchThumbnail(entityId, baseUrl, entityPicture, isInternal = true, force = true)

                // Re-read the entity after the prefetch network round-trip. The parallel
                // subscribeToEntitiesForServer flow may have written a fresh state into the cache
                // while the prefetch was in flight, and emitting the stale snapshot captured at
                // the top of this iteration would race against (and visibly overwrite) the live
                // controls that subscribeToEntitiesForServer just delivered to the subscriber.
                val entity = cachedEntities[serverId]?.get(entityId) ?: cachedEntity

                // Re-send the camera control with fresh thumbnail
                val info = HaControlInfo(
                    systemId = controlId,
                    entityId = entityId,
                    serverId = serverId,
                    area = getAreaForEntity(entityId, serverId),
                    baseUrl = baseUrl,
                )
                val control = domainToHaControl["camera"]?.createControl(applicationContext, entity, info)
                if (control != null) {
                    subscriber.onNext(control)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh camera thumbnail for $controlId")
            }
        }
    }

    private fun sendCachedControlsImmediately(
        controlIds: MutableList<String>,
        subscriber: Flow.Subscriber<in Control>,
    ) {
        // Runs on the main thread (RequestHandler.handleMessage), so MUST NOT do any disk I/O —
        // the project's StrictMode + CrashFailFastHandler will kill the process on a violation.
        // The cached entity state is only trustworthy while the WebSocket is currently Active
        // AND Android reports that the device has network. Checking just WebSocketState is not
        // enough: OkHttp only transitions the state to Closed after a ping/read timeout
        // (up to ~45s after the link actually drops), so in that window `getConnectionState()`
        // still returns Active and would let us render hours-old cached values as "OK".
        // `NetworkHelper.hasActiveNetwork()` is a synchronous ConnectivityManager lookup that
        // reflects the Android-level network state immediately.
        val hasNetwork = networkHelper.hasActiveNetwork()
        val connectionStateByServer = HashMap<Int, Boolean>()
        var sentCached = 0
        var sentPlaceholder = 0
        controlIds.forEach { controlId ->
            try {
                val serverId = controlId.split(".")[0].toIntOrNull()
                    ?: lastControlEntityIds.keys.firstOrNull() ?: return@forEach
                val entityId = controlId.removePrefix("$serverId.")
                val entity = cachedEntities[serverId]?.get(entityId)
                val isConnected = connectionStateByServer.getOrPut(serverId) {
                    isLiveConnectionTrustworthy(
                        webSocketState = webSocketRepositoryCache[serverId]?.getConnectionState(),
                        hasActiveNetwork = hasNetwork,
                    )
                }

                if (entity != null && isConnected) {
                    val domain = entityId.split(".")[0]
                    val haControl = domainToHaControl[domain] ?: return@forEach
                    val info = HaControlInfo(
                        systemId = controlId,
                        entityId = entityId,
                        serverId = serverId,
                        area = getAreaForEntity(entityId, serverId),
                        baseUrl = lastBaseUrl[serverId],
                    )
                    val control = haControl.createControl(applicationContext, entity, info)
                    subscriber.onNext(control)
                    sentCached++
                } else {
                    // Either no cached entity (cold start after force-stop) or WebSocket is not
                    // currently Active (phone offline, HA restarting, …). In both cases we don't
                    // know the real state, so emit a STATUS_UNKNOWN placeholder. The async path
                    // will replace it with real data once the subscription delivers events.
                    val placeholderEntity = getFailedEntity(entityId, Exception())
                        .copy(state = "loading")
                    domainToHaControl["ha_failed"]?.createControl(
                        applicationContext,
                        placeholderEntity,
                        HaControlInfo(
                            systemId = controlId,
                            entityId = entityId,
                            serverId = serverId,
                        ),
                    )?.let { control ->
                        subscriber.onNext(control)
                        sentPlaceholder++
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send control for $controlId")
            }
        }
        Timber.d(
            "Sent $sentCached cached + $sentPlaceholder placeholder controls " +
                "(hasNetwork=$hasNetwork wsConnected=${connectionStateByServer.values})",
        )
    }

    private fun getFailedEntity(entityId: String, exception: Exception): Entity {
        return Entity(
            entityId = entityId,
            state = if (exception is HttpException && exception.code() == 404) "notfound" else "exception",
            attributes = mapOf<String, String>(),
            lastChanged = LocalDateTime.now(),
            lastUpdated = LocalDateTime.now(),
        )
    }

    private fun getAreaForEntity(entityId: String, serverId: Int) = RegistriesDataHandler.getAreaForEntity(
        entityId,
        areaRegistry[serverId],
        deviceRegistry[serverId],
        entityRegistry[serverId],
    )

    private suspend fun entityRequiresAuth(entityId: String, serverId: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val setting = prefsRepository.getControlsAuthRequired()
            if (setting == ControlsAuthRequiredSetting.SELECTION) {
                val includeList = prefsRepository.getControlsAuthEntities()
                includeList.contains("$serverId.$entityId")
            } else {
                setting == ControlsAuthRequiredSetting.ALL
            }
        } else {
            false
        }
    }

    private suspend fun splitMultiServersIntoStructures(): Boolean {
        return prefsRepository.getControlsEnableStructure()
    }
}
