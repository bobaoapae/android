package io.homeassistant.companion.android.common.data.websocket.impl

import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.WebSocketCore
import io.homeassistant.companion.android.common.data.websocket.WebSocketState
import io.homeassistant.companion.android.common.data.websocket.impl.WebSocketConstants.SUBSCRIBE_TYPE_ASSIST_PIPELINE_RUN
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEvent
import io.homeassistant.companion.android.common.data.websocket.impl.entities.DeviceRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.EntityRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.RawMessageSocketResponse
import io.homeassistant.companion.android.common.util.VOICE_SAMPLE_RATE
import io.homeassistant.companion.android.common.util.kotlinJsonMapper
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.testing.unit.ConsoleLogExtension
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@ExtendWith(ConsoleLogExtension::class)
class WebSocketRepositoryImplTest {

    private lateinit var webSocketCore: WebSocketCore
    private lateinit var serverManager: ServerManager
    private lateinit var repository: WebSocketRepositoryImpl

    @BeforeEach
    fun setUp() {
        webSocketCore = mockk(relaxed = true)
        serverManager = mockk(relaxed = true)
        repository = WebSocketRepositoryImpl(webSocketCore, serverManager)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    inner class RunAssistPipelineForVoice {

        private fun captureSubscribeData(server: Server? = null): CapturingSlot<Map<String, Any?>> {
            val dataSlot = slot<Map<String, Any?>>()
            coEvery {
                webSocketCore.subscribeTo<AssistPipelineEvent>(
                    type = SUBSCRIBE_TYPE_ASSIST_PIPELINE_RUN,
                    data = capture(dataSlot),
                    timeout = any(),
                )
            } returns emptyFlow()
            coEvery { webSocketCore.server() } returns server
            return dataSlot
        }

        @Test
        fun `Given wake word When running pipeline Then wake_word_phrase is included in input`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = "okay nabu",
            )

            coVerify {
                webSocketCore.subscribeTo<AssistPipelineEvent>(
                    type = SUBSCRIBE_TYPE_ASSIST_PIPELINE_RUN,
                    data = any(),
                    timeout = any(),
                )
            }

            @Suppress("UNCHECKED_CAST")
            val input = dataSlot.captured["input"] as Map<String, Any?>
            assertEquals("okay nabu", input["wake_word_phrase"])
        }

        @Test
        fun `Given no wake word When running pipeline Then wake_word_phrase is not included`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            @Suppress("UNCHECKED_CAST")
            val input = dataSlot.captured["input"] as Map<String, Any?>
            assertFalse(input.containsKey("wake_word_phrase"))
        }

        @ParameterizedTest(name = "Given outputTts={0} When running pipeline Then end_stage is {1}")
        @CsvSource("true, tts", "false, intent")
        fun `Given outputTts When running pipeline Then end_stage matches`(
            outputTts: Boolean,
            expectedEndStage: String,
        ) = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = outputTts,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            assertEquals(expectedEndStage, dataSlot.captured["end_stage"])
        }

        @Test
        fun `Given pipelineId When running pipeline Then pipeline is included`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = "my-pipeline-id",
                conversationId = null,
                wakeWordPhrase = null,
            )

            assertEquals("my-pipeline-id", dataSlot.captured["pipeline"])
        }

        @Test
        fun `Given no pipelineId When running pipeline Then pipeline is not included`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            assertFalse(dataSlot.captured.containsKey("pipeline"))
        }

        @Test
        fun `Given server with deviceRegistryId When running pipeline Then device_id is included`() = runTest {
            val dataSlot = captureSubscribeData(server = createServer(deviceRegistryId = "device-123"))

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            assertEquals("device-123", dataSlot.captured["device_id"])
        }

        @Test
        fun `Given server without deviceRegistryId When running pipeline Then device_id is not included`() = runTest {
            val dataSlot = captureSubscribeData(server = createServer(deviceRegistryId = null))

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            assertFalse(dataSlot.captured.containsKey("device_id"))
        }

        @Test
        fun `Given conversationId When running pipeline Then conversation_id is included`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = "conv-456",
                wakeWordPhrase = null,
            )

            assertEquals("conv-456", dataSlot.captured["conversation_id"])
        }

        @Test
        fun `Given sampleRate When running pipeline Then sample_rate is included in input`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = 16000,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            @Suppress("UNCHECKED_CAST")
            val input = dataSlot.captured["input"] as Map<String, Any?>
            assertEquals(16000, input["sample_rate"])
        }

        @Test
        fun `When running pipeline Then start_stage is always stt`() = runTest {
            val dataSlot = captureSubscribeData()

            repository.runAssistPipelineForVoice(
                sampleRate = VOICE_SAMPLE_RATE,
                outputTts = true,
                pipelineId = null,
                conversationId = null,
                wakeWordPhrase = null,
            )

            assertEquals("stt", dataSlot.captured["start_stage"])
        }
    }

    @Nested
    inner class RegistryCache {

        private fun fakeAreaResponse(): RawMessageSocketResponse {
            val fakeResult = kotlinJsonMapper.parseToJsonElement(
                kotlinJsonMapper.encodeToString(
                    listOf(AreaRegistryResponse(areaId = "area1", name = "Living Room")),
                ),
            )
            return mockk<RawMessageSocketResponse> {
                every { success } returns true
                every { result } returns fakeResult
                every { error } returns null
            }
        }

        private fun fakeDeviceResponse(): RawMessageSocketResponse {
            val fakeResult = kotlinJsonMapper.parseToJsonElement(
                kotlinJsonMapper.encodeToString(
                    listOf(DeviceRegistryResponse(id = "device1", name = "Test Device")),
                ),
            )
            return mockk<RawMessageSocketResponse> {
                every { success } returns true
                every { result } returns fakeResult
                every { error } returns null
            }
        }

        private fun fakeEntityResponse(): RawMessageSocketResponse {
            val fakeResult = kotlinJsonMapper.parseToJsonElement(
                kotlinJsonMapper.encodeToString(
                    listOf(EntityRegistryResponse(entityId = "light.test")),
                ),
            )
            return mockk<RawMessageSocketResponse> {
                every { success } returns true
                every { result } returns fakeResult
                every { error } returns null
            }
        }

        @Test
        fun `Given area registry already cached when getAreaRegistry then does not call WebSocket`() = runTest {
            coEvery { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/area_registry/list" }) } returns fakeAreaResponse()
            coEvery { webSocketCore.getConnectionState() } returns WebSocketState.Active

            repository.getAreaRegistry()
            repository.getAreaRegistry()

            // Should only call sendMessage once (second call uses cache)
            coVerify(exactly = 1) { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/area_registry/list" }) }
        }

        @Test
        fun `Given WebSocket disconnected and cache exists when getAreaRegistry then returns cached data`() = runTest {
            coEvery { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/area_registry/list" }) } returns fakeAreaResponse()
            coEvery { webSocketCore.getConnectionState() } returns WebSocketState.Active

            // First call with active connection to populate cache
            val firstResult = repository.getAreaRegistry()
            assertEquals(1, firstResult?.size)

            // Now simulate disconnection - cache should still be used even if stale
            coEvery { webSocketCore.getConnectionState() } returns WebSocketState.ClosedOther

            val secondResult = repository.getAreaRegistry()
            assertEquals(1, secondResult?.size)

            // Still only 1 WebSocket call because cache is used when disconnected
            coVerify(exactly = 1) { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/area_registry/list" }) }
        }

        @Test
        fun `Given device registry already cached when getDeviceRegistry then does not call WebSocket`() = runTest {
            coEvery { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/device_registry/list" }) } returns fakeDeviceResponse()
            coEvery { webSocketCore.getConnectionState() } returns WebSocketState.Active

            repository.getDeviceRegistry()
            repository.getDeviceRegistry()

            coVerify(exactly = 1) { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/device_registry/list" }) }
        }

        @Test
        fun `Given entity registry already cached when getEntityRegistry then does not call WebSocket`() = runTest {
            coEvery { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/entity_registry/list" }) } returns fakeEntityResponse()
            coEvery { webSocketCore.getConnectionState() } returns WebSocketState.Active

            repository.getEntityRegistry()
            repository.getEntityRegistry()

            coVerify(exactly = 1) { webSocketCore.sendMessage(match<Map<String, Any?>> { it["type"] == "config/entity_registry/list" }) }
        }
    }

    private fun createServer(deviceRegistryId: String? = null): Server {
        return Server(
            id = 1,
            _name = "Test Server",
            deviceRegistryId = deviceRegistryId,
            connection = ServerConnectionInfo(
                externalUrl = "https://example.com",
            ),
            session = ServerSessionInfo(),
            user = ServerUserInfo(),
        )
    }
}
