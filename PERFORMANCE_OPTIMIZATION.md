# MockkHttp Performance Optimization Guide

## 📊 Executive Summary

Este documento detalla los problemas de rendimiento críticos encontrados en MockkHttp cuando se usa en escenarios de **alta carga** (proyectos grandes con cientos de requests simultáneas y múltiples proyectos Android Studio abiertos).

**Objetivo**: Hacer que el plugin funcione de manera eficiente sin limitar funcionalidad, soportando:
- ✅ Cientos de requests simultáneas por segundo
- ✅ Múltiples proyectos Android Studio abiertos
- ✅ Apps que hacen cientos de llamadas HTTP
- ✅ Modo Debug sin colapsar la app o el plugin

---

## 🔴 PROBLEMA 1: Thread Explosion - Thread Pool sin Límites

### Ubicación
**Archivo**: `GlobalOkHttpInterceptorServer.kt:307-309`

### Problema Actual
```kotlin
// Handle each client in separate thread
thread(isDaemon = true) {
    handleClient(clientSocket)
}
```

**Impacto**:
- Crea un thread NUEVO por cada request HTTP sin límite alguno
- 100 requests simultáneas = 100 threads activos
- 500 requests = 500 threads → **Colapso total del sistema**
- Cada thread consume ~1MB de stack + overhead del scheduler
- CPU se satura haciendo context switching entre cientos de threads

### Solución Propuesta
Implementar **Kotlin Coroutines con Channel para Backpressure**:

```kotlin
@Service(Service.Level.APP)
class GlobalOkHttpInterceptorServer {

    private val logger = Logger.getInstance(GlobalOkHttpInterceptorServer::class.java)
    private val gson = Gson()

    // CoroutineScope para el servidor
    private val serverScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.IO +
        CoroutineName("MockkHttp-GlobalServer")
    )

    // Channel con backpressure (buffer limitado)
    private val requestChannel = Channel<Socket>(capacity = 500)  // Buffer de 500 requests

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    @Synchronized
    fun ensureStarted(): Boolean {
        if (isRunning) return true

        logger.info("🚀 Starting Global OkHttp Interceptor Server on port $SERVER_PORT")

        return try {
            serverSocket = ServerSocket(SERVER_PORT)
            isRunning = true

            // Lanzar servidor en coroutine
            serverScope.launch {
                runServer()
            }

            // Lanzar workers (pool de coroutines para procesar requests)
            repeat(50) { workerId ->  // 50 workers concurrentes
                serverScope.launch {
                    processRequests(workerId)
                }
            }

            logger.info("✅ Global interceptor server listening on port $SERVER_PORT")
            true

        } catch (e: Exception) {
            logger.error("❌ Failed to start global interceptor server", e)
            isRunning = false
            false
        }
    }

    /**
     * Server loop - acepta conexiones y las envía al channel.
     */
    private suspend fun runServer() {
        logger.info("🔄 Global server loop started")
        try {
            while (isRunning) {
                try {
                    val clientSocket = withContext(Dispatchers.IO) {
                        serverSocket?.accept()
                    } ?: break

                    logger.debug("📱 Client connected: ${clientSocket.inetAddress.hostAddress}")

                    // Enviar al channel (con backpressure)
                    // Si el channel está lleno, esta línea SUSPENDE (no bloquea thread)
                    try {
                        requestChannel.send(clientSocket)
                    } catch (e: ClosedSendChannelException) {
                        logger.debug("Channel closed, rejecting connection")
                        clientSocket.close()
                        break
                    }

                } catch (e: SocketException) {
                    if (isRunning) logger.error("Socket error", e)
                    break
                } catch (e: Exception) {
                    if (isRunning) logger.error("Error accepting client connection", e)
                }
            }
        } finally {
            logger.debug("Global server loop ended")
        }
    }

    /**
     * Worker coroutine - procesa requests del channel.
     * Múltiples workers pueden procesar concurrentemente.
     */
    private suspend fun processRequests(workerId: Int) {
        logger.debug("Worker $workerId started")

        try {
            for (clientSocket in requestChannel) {  // Consume del channel
                try {
                    handleClient(clientSocket)
                } catch (e: Exception) {
                    logger.error("Worker $workerId: Error handling client", e)
                }
            }
        } finally {
            logger.debug("Worker $workerId stopped")
        }
    }

    /**
     * Handle a single client connection (ahora es suspending function).
     */
    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            socket.use { clientSocket ->
                try {
                    val reader = BufferedReader(clientSocket.getInputStream().reader())
                    val writer = PrintWriter(clientSocket.getOutputStream(), true)

                    val json = reader.readLine()
                    if (json == null) {
                        logger.debug("Client disconnected without sending data")
                        return@use
                    }

                    // Handle PING
                    if (json == "PING") {
                        writer.println("PONG")
                        logger.debug("📡 PING received, sent PONG")
                        return@use
                    }

                    // Parse and route flow
                    val flowData = gson.fromJson(json, AndroidFlowData::class.java)
                    logger.debug("${flowData.request.method} ${flowData.request.url}")

                    val response = routeFlow(flowData)
                    writer.println(gson.toJson(response))

                } catch (e: Exception) {
                    logger.error("Error handling client", e)
                }
            }
        }
    }

    @Synchronized
    private fun stop() {
        if (!isRunning) return

        logger.info("🛑 Stopping global interceptor server...")
        isRunning = false

        try {
            // Cerrar channel (workers terminan cuando consumen pendientes)
            requestChannel.close()

            // Cancelar scope (cancela todas las coroutines)
            serverScope.cancel()

            serverSocket?.close()
            logger.info("✅ Global interceptor server stopped")
        } catch (e: Exception) {
            logger.error("Error stopping global server", e)
        }

        serverSocket = null
    }
}
```

**Beneficios de Coroutines sobre ExecutorService**:
- ✅ **Lightweight**: 50 coroutines ≈ 500KB vs 50 threads ≈ 50MB de memoria
- ✅ **Backpressure nativo**: `Channel.send()` suspende (no bloquea) cuando está lleno
- ✅ **Structured concurrency**: `serverScope.cancel()` limpia todo automáticamente
- ✅ **Non-blocking I/O**: `accept()` y `handleClient()` liberan threads mientras esperan
- ✅ **Escalable**: Soporta 100,000+ coroutines concurrentes vs ~1,000 threads max

**Esfuerzo**: ~3 horas (incluye agregar dependencia de coroutines si no está)
**Prioridad**: 🔴 CRÍTICA

---

## 🔴 PROBLEMA 2: Debug Mode Blocking - Bloqueo Masivo de Threads

### Ubicación
- **Archivo 1**: `OkHttpInterceptorServer.kt:270-346` (`showInterceptDialogAndWait()`)
- **Archivo 2**: `MockkHttpInterceptor.kt:212-254` (Android side)

### Problema Actual

**En Plugin (IntelliJ)**:
```kotlin
private fun showInterceptDialogAndWait(flowData: HttpFlowData): Pair<ModifiedResponseData, Boolean> {
    val latch = CountDownLatch(1)

    SwingUtilities.invokeLater {
        val dialog = DebugInterceptDialog(project, flowData)
        dialog.showAndGet()  // MODAL DIALOG - bloquea hasta que usuario actúe
        latch.countDown()
    }

    latch.await(5, TimeUnit.MINUTES)  // BLOQUEA THREAD DEL SOCKET ⚠️
}
```

**En Android App**:
```kotlin
// MockkHttpInterceptor.kt:231
// WAIT for modified response (blocks thread)
val reader = it.getInputStream().bufferedReader()
val modifiedJson = reader.readLine()  // BLOQUEA THREAD DE OkHttp ⚠️
```

**Impacto en cascada**:
1. Usuario abre Debug Mode
2. App hace 100 requests simultáneas
3. Cada request crea un thread en GlobalServer (PROBLEMA #1)
4. Cada thread queda BLOQUEADO esperando que el usuario cierre el diálogo
5. **100 diálogos apilados** esperando atención del usuario
6. 100 threads bloqueados en Android consumiendo el pool de OkHttp
7. La app se congela porque OkHttp no tiene threads disponibles
8. El plugin se ralentiza porque tiene 100 threads zombie

### Solución Propuesta

**Implementar Queue con Coroutines + CompletableDeferred**:

```kotlin
@Service(Service.Level.PROJECT)
class OkHttpInterceptorServer(private val project: Project) {

    // CoroutineScope del proyecto
    private val projectScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        CoroutineName("MockkHttp-Project-${project.name}")
    )

    // StateFlow para pending debug requests (observable en UI)
    private val pendingDebugRequests = MutableStateFlow<List<PendingDebugRequest>>(emptyList())

    data class PendingDebugRequest(
        val flowId: String,
        val flowData: HttpFlowData,
        val timestamp: Long,
        val responseDeferred: CompletableDeferred<ModifiedResponseData>  // Promesa de respuesta
    )

    /**
     * Handle flow - ahora es suspending function.
     */
    private suspend fun handleFlow(androidFlow: AndroidFlowData): ModifiedResponseData {
        val httpFlowData = convertToHttpFlowData(androidFlow)

        return when (currentMode) {
            Mode.RECORDING -> {
                flowStore.addFlow(httpFlowData)
                ModifiedResponseData.original()
            }

            Mode.DEBUG -> {
                // Agregar a queue de debug
                val deferred = CompletableDeferred<ModifiedResponseData>()

                val pendingRequest = PendingDebugRequest(
                    flowId = httpFlowData.flowId,
                    flowData = httpFlowData,
                    timestamp = System.currentTimeMillis(),
                    responseDeferred = deferred
                )

                // Actualizar StateFlow (thread-safe, reactivo)
                pendingDebugRequests.update { current ->
                    current + pendingRequest
                }

                // Notificar UI que hay pending request
                withContext(Dispatchers.EDT) {  // EDT = Event Dispatch Thread
                    notifyPendingRequest()
                }

                // ESPERAR respuesta con timeout (SUSPENDE, no bloquea thread) ⭐
                withTimeoutOrNull(30_000) {  // 30 segundos timeout
                    deferred.await()
                } ?: run {
                    // Timeout - remover de queue
                    pendingDebugRequests.update { current ->
                        current.filter { it.flowId != httpFlowData.flowId }
                    }
                    logger.warn("⚠️ Debug request timeout for ${httpFlowData.flowId}")
                    ModifiedResponseData.original()
                }
            }

            Mode.MOCKK -> {
                val matchingRule = findMatchingMockRule(httpFlowData)
                if (matchingRule != null) {
                    flowStore.addFlow(httpFlowData.copy(mockApplied = true))
                    ModifiedResponseData(
                        statusCode = matchingRule.statusCode,
                        headers = matchingRule.headers,
                        body = matchingRule.content
                    )
                } else {
                    flowStore.addFlow(httpFlowData)
                    ModifiedResponseData.original()
                }
            }

            Mode.MOCKK_DEBUG -> {
                // Similar a DEBUG pero con mock aplicado
                // ... (código similar)
            }
        }
    }

    /**
     * Método llamado desde UI cuando usuario responde al diálogo.
     */
    fun respondToPendingRequest(flowId: String, response: ModifiedResponseData) {
        projectScope.launch {
            val request = pendingDebugRequests.value.find { it.flowId == flowId }

            if (request != null) {
                // Completar la promesa (desbloquea el await() en handleFlow)
                request.responseDeferred.complete(response)

                // Remover de queue
                pendingDebugRequests.update { current ->
                    current.filter { it.flowId != flowId }
                }

                logger.info("✅ User responded to debug request: $flowId")
            }
        }
    }

    /**
     * UI Panel observa el StateFlow y muestra pending requests reactivamente.
     */
    fun observePendingRequests(onUpdate: (List<PendingDebugRequest>) -> Unit) {
        projectScope.launch(Dispatchers.EDT) {
            pendingDebugRequests.collect { pending ->
                onUpdate(pending)
            }
        }
    }
}
```

**UI Panel para pending requests**:

```kotlin
class InspectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val pendingRequestsLabel = JLabel("Pending: 0")

    init {
        // Observar pending requests reactivamente
        interceptorServer.observePendingRequests { pending ->
            pendingRequestsLabel.text = "Pending: ${pending.size}"

            // Auto-mostrar diálogo para el primero
            if (pending.isNotEmpty() && !isDialogOpen) {
                showDebugDialog(pending.first())
            }
        }
    }

    private fun showDebugDialog(request: PendingDebugRequest) {
        isDialogOpen = true

        val dialog = DebugInterceptDialog(project, request.flowData)
        dialog.show()

        // Cuando usuario cierra diálogo, enviar respuesta
        val response = if (dialog.exitCode == DialogWrapper.OK_EXIT_CODE) {
            dialog.getModifiedResponse() ?: ModifiedResponseData.original()
        } else {
            ModifiedResponseData.original()
        }

        interceptorServer.respondToPendingRequest(request.flowId, response)
        isDialogOpen = false
    }
}
```

**Beneficios de Coroutines**:
- ✅ **CompletableDeferred**: Promesa de respuesta, más elegante que `CountDownLatch`
- ✅ **No bloquea threads**: `await()` suspende la coroutine, libera el thread
- ✅ **StateFlow**: Observable reactive, UI se actualiza automáticamente
- ✅ **Timeout integrado**: `withTimeoutOrNull()` built-in en coroutines
- ✅ **Dispatchers.EDT**: Integración nativa con EDT de IntelliJ
- ✅ **Cancelación estructurada**: Al cerrar proyecto, todo se limpia automáticamente

**Esfuerzo**: ~7 horas (más complejo pero mucho mejor arquitectura)
**Prioridad**: 🔴 CRÍTICA

---

## 🔴 PROBLEMA 3: Memory Explosion - FlowStore sin Límites Efectivos

### Ubicación
**Archivo**: `FlowStore.kt:40`

### Problema Actual
```kotlin
companion object {
    // Maximum number of flows to keep in memory
    private const val MAX_FLOWS = 1000
}
```

**Cálculo real de memoria**:
- MAX_FLOWS = 1000
- Cada flow puede tener response body de hasta **5MB** (según `MockkHttpInterceptor.kt:308`)
- Memoria potencial: 1000 × 5MB = **5GB** 🔥

**Impacto**:
- Con una app que hace 100s de requests, se llena rápidamente
- Flows nunca se limpian hasta llegar a 1000
- Garbage Collector trabaja constantemente
- IntelliJ se ralentiza por presión de memoria

### Solución Propuesta

**Implementar Memory Budget con Cleanup Inteligente**:

```kotlin
class FlowStore(project: Project) {

    companion object {
        // Configuración de límites
        private const val MAX_FLOWS = 200  // Reducir de 1000 a 200
        private const val MAX_MEMORY_MB = 50  // Máximo 50MB de flows en memoria
        private const val LARGE_RESPONSE_THRESHOLD = 100_000  // 100KB
        private const val CLEANUP_AGE_MINUTES = 15  // Limpiar flows >15 min
    }

    // Tracking de memoria usada
    @Volatile
    private var estimatedMemoryUsageBytes = 0L

    fun addFlow(flow: HttpFlowData) {
        // Estimar tamaño del flow
        val flowSize = estimateFlowSize(flow)

        // Si excede budget de memoria, hacer cleanup agresivo
        if (estimatedMemoryUsageBytes + flowSize > MAX_MEMORY_MB * 1024 * 1024) {
            logger.warn("⚠️ Memory budget exceeded, performing cleanup...")
            performMemoryCleanup()
        }

        // Comprimir response bodies grandes
        val optimizedFlow = if (flowSize > LARGE_RESPONSE_THRESHOLD) {
            compressLargeFlow(flow)
        } else {
            flow
        }

        flows[flow.flowId] = optimizedFlow
        flowOrder.add(flow.flowId)
        estimatedMemoryUsageBytes += flowSize

        // Cleanup por edad y cantidad
        while (flowOrder.size > MAX_FLOWS || shouldCleanupOldFlows()) {
            removeOldestFlow()
        }

        // Notificar listeners...
    }

    private fun estimateFlowSize(flow: HttpFlowData): Long {
        var size = 0L
        size += flow.request.url.length * 2  // chars = 2 bytes
        size += flow.request.content.length * 2
        size += flow.response?.content?.length?.times(2) ?: 0
        size += flow.request.headers.toString().length * 2
        size += flow.response?.headers?.toString()?.length?.times(2) ?: 0
        size += 500  // Overhead de objeto
        return size
    }

    private fun compressLargeFlow(flow: HttpFlowData): HttpFlowData {
        // Opción 1: Truncar response body
        val truncatedContent = flow.response?.content?.take(50_000) +
            "\n\n[... Response truncated to save memory. ${flow.response?.content?.length} total bytes]"

        return flow.copy(
            response = flow.response?.copy(content = truncatedContent)
        )

        // Opción 2: Comprimir con GZIP (más avanzado)
        // return compressWithGzip(flow)
    }

    private fun performMemoryCleanup() {
        // 1. Remover flows >15 minutos
        val now = System.currentTimeMillis()
        val cutoffTime = now - (CLEANUP_AGE_MINUTES * 60 * 1000)

        flowOrder.toList().forEach { flowId ->
            val flow = flows[flowId]
            if (flow != null && flow.timestamp * 1000 < cutoffTime) {
                removeFlow(flowId)
                logger.debug("Cleaned up old flow: $flowId")
            }
        }

        // 2. Si todavía excede memoria, remover flows más grandes
        if (estimatedMemoryUsageBytes > MAX_MEMORY_MB * 1024 * 1024) {
            val sortedBySize = flows.values.sortedByDescending { estimateFlowSize(it) }
            sortedBySize.take(50).forEach { flow ->
                removeFlow(flow.flowId)
                logger.debug("Cleaned up large flow: ${flow.flowId}")
            }
        }
    }

    private fun shouldCleanupOldFlows(): Boolean {
        if (flowOrder.isEmpty()) return false

        val oldestId = flowOrder.firstOrNull() ?: return false
        val oldestFlow = flows[oldestId] ?: return false

        val ageMinutes = (System.currentTimeMillis() - oldestFlow.timestamp * 1000) / 60000
        return ageMinutes > CLEANUP_AGE_MINUTES
    }

    private fun removeFlow(flowId: String) {
        val removed = flows.remove(flowId)
        flowOrder.remove(flowId)

        if (removed != null) {
            estimatedMemoryUsageBytes -= estimateFlowSize(removed)
            if (removed.paused) pausedFlowsCount--
        }
    }
}
```

**Beneficios**:
- ✅ Memoria predecible y controlada (máximo 50MB)
- ✅ Cleanup automático de flows antiguos
- ✅ Compresión de responses grandes
- ✅ Mejor rendimiento del GC

**Esfuerzo**: ~4 horas
**Prioridad**: 🔴 CRÍTICA

---

## 🔴 PROBLEMA 4: UI Blocking - Updates sin Batching

### Ubicación
**Archivo**: `InspectorPanel.kt:231-258`

### Problema Actual
```kotlin
// Listen to flow changes
flowStore.addFlowAddedListener { flow ->
    SwingUtilities.invokeLater {
        allFlows.add(flow)
        // Apply filter
        if (matchesSearchQuery(flow, searchQuery)) {
            flowListModel.addElement(flow)  // Update UI INMEDIATAMENTE ⚠️
        }
    }
}

flowStore.addFlowUpdatedListener { updatedFlow ->
    SwingUtilities.invokeLater {
        // Update in allFlows
        val indexInAll = allFlows.indexOfFirst { it.flowId == updatedFlow.flowId }  // O(n) ⚠️
        if (indexInAll >= 0) {
            allFlows[indexInAll] = updatedFlow
        }

        // Update in filtered list if present
        for (i in 0 until flowListModel.size()) {  // O(n) OTRA VEZ ⚠️
            val existingFlow = flowListModel.getElementAt(i)
            if (existingFlow.flowId == updatedFlow.flowId) {
                flowListModel.setElementAt(updatedFlow, i)
                break
            }
        }
    }
}
```

**Impacto**:
- 100 requests/segundo = 100 `SwingUtilities.invokeLater()` calls/segundo
- Cada update busca en lista completa con O(n)
- EDT (Event Dispatch Thread) se satura
- UI se congela y deja de responder
- Scrolling se vuelve lento

### Solución Propuesta

**Implementar Reactive Streams con Flow (Kotlin Coroutines)**:

#### Paso 1: FlowStore con SharedFlow

```kotlin
@Service(Service.Level.PROJECT)
class FlowStore(project: Project) {

    // SharedFlow para emitir eventos (hot stream, múltiples collectors)
    private val _flowAdded = MutableSharedFlow<HttpFlowData>(
        extraBufferCapacity = 100,  // Buffer para evitar backpressure
        onBufferOverflow = BufferOverflow.DROP_OLDEST  // Drop antiguos si se llena
    )
    val flowAdded: SharedFlow<HttpFlowData> = _flowAdded.asSharedFlow()

    private val _flowUpdated = MutableSharedFlow<HttpFlowData>(
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val flowUpdated: SharedFlow<HttpFlowData> = _flowUpdated.asSharedFlow()

    private val _flowsCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
    val flowsCleared: SharedFlow<Unit> = _flowsCleared.asSharedFlow()

    fun addFlow(flow: HttpFlowData) {
        // ... lógica de agregar flow ...

        // Emitir evento (non-blocking)
        _flowAdded.tryEmit(flow)
    }

    fun updateFlow(flow: HttpFlowData) {
        // ... lógica de actualizar flow ...

        _flowUpdated.tryEmit(flow)
    }

    fun clearAllFlows() {
        flows.clear()
        flowOrder.clear()

        _flowsCleared.tryEmit(Unit)
    }
}
```

#### Paso 2: InspectorPanel con Reactive UI

```kotlin
class InspectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    // CoroutineScope para el panel
    private val panelScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        CoroutineName("InspectorPanel")
    )

    // HashMap para lookups O(1)
    private val flowsMap = ConcurrentHashMap<String, HttpFlowData>()
    private val allFlows = mutableListOf<HttpFlowData>()

    init {
        // Observar flows added con batching y debouncing automático
        panelScope.launch {
            flowStore.flowAdded
                .buffer(capacity = 50)  // Batching: acumula hasta 50 flows
                .chunked(50)  // Agrupa en lotes de máximo 50
                .debounce(300)  // Espera 300ms de inactividad antes de procesar
                .flowOn(Dispatchers.Default)  // Procesar en background thread
                .collect { flowsBatch ->
                    // Actualizar en EDT
                    withContext(Dispatchers.EDT) {
                        processBatchAdd(flowsBatch)
                    }
                }
        }

        // Observar flows updated
        panelScope.launch {
            flowStore.flowUpdated
                .buffer(capacity = 50)
                .chunked(50)
                .debounce(300)
                .flowOn(Dispatchers.Default)
                .collect { flowsBatch ->
                    withContext(Dispatchers.EDT) {
                        processBatchUpdate(flowsBatch)
                    }
                }
        }

        // Observar flows cleared
        panelScope.launch(Dispatchers.EDT) {
            flowStore.flowsCleared.collect {
                flowsMap.clear()
                allFlows.clear()
                flowListModel.clear()
            }
        }
    }

    private fun processBatchAdd(flows: List<HttpFlowData>) {
        // Procesar batch de flows nuevos
        flows.forEach { flow ->
            flowsMap[flow.flowId] = flow
            allFlows.add(flow)

            if (matchesSearchQuery(flow, searchQuery)) {
                flowListModel.addElement(flow)
            }
        }

        logger.debug("Processed batch add: ${flows.size} flows")
    }

    private fun processBatchUpdate(flows: List<HttpFlowData>) {
        // Procesar batch de flows actualizados
        flows.forEach { updatedFlow ->
            flowsMap[updatedFlow.flowId] = updatedFlow

            // Update in allFlows
            val index = allFlows.indexOfFirst { it.flowId == updatedFlow.flowId }
            if (index >= 0) {
                allFlows[index] = updatedFlow
            }

            // Update in UI model
            for (i in 0 until flowListModel.size()) {
                if (flowListModel.getElementAt(i).flowId == updatedFlow.flowId) {
                    flowListModel.setElementAt(updatedFlow, i)
                    break
                }
            }
        }

        logger.debug("Processed batch update: ${flows.size} flows")
    }

    override fun removeNotify() {
        super.removeNotify()
        panelScope.cancel()  // Cleanup automático
    }
}
```

#### Extensión para `chunked()` con timeout

```kotlin
// Extensión útil para chunking con timeout
fun <T> Flow<T>.chunked(
    size: Int,
    timeoutMillis: Long = 500
): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    var lastEmitTime = System.currentTimeMillis()

    collect { value ->
        buffer.add(value)

        val now = System.currentTimeMillis()
        val shouldEmitBySize = buffer.size >= size
        val shouldEmitByTime = (now - lastEmitTime) >= timeoutMillis

        if (shouldEmitBySize || shouldEmitByTime) {
            emit(buffer.toList())
            buffer.clear()
            lastEmitTime = now
        }
    }

    // Emit remaining
    if (buffer.isNotEmpty()) {
        emit(buffer.toList())
    }
}
```

**Beneficios de Flow/Coroutines**:
- ✅ **Batching automático**: `.buffer()` y `.chunked()` agrupan eventos
- ✅ **Debouncing built-in**: `.debounce()` reduce frecuencia de updates
- ✅ **Backpressure handling**: `BufferOverflow.DROP_OLDEST` evita overflow
- ✅ **Reactive y declarativo**: UI se actualiza automáticamente al observar Flow
- ✅ **Thread-safety**: Todo manejo de threading es automático con Dispatchers
- ✅ **Cancelación estructurada**: `panelScope.cancel()` limpia todo

**Alternativa: StateFlow para datos que mantienen estado**:
```kotlin
// Si necesitas que nuevo subscriber reciba último valor
private val _currentFlows = MutableStateFlow<List<HttpFlowData>>(emptyList())
val currentFlows: StateFlow<List<HttpFlowData>> = _currentFlows.asStateFlow()
```

**Esfuerzo**: ~5 horas
**Prioridad**: 🔴 CRÍTICA

---

## 🔴 PROBLEMA 5: ADB Operations - Bloqueo de Android Studio

### Ubicación
**Archivo**: `AppManager.kt:32-81, 188-235`

### Problema Actual
```kotlin
fun getInstalledApps(serialNumber: String, includeSystem: Boolean = false): List<AppInfo> {
    // Este método se ejecuta SÍNCRONAMENTE en foreground

    // 1. Ejecuta shell command (puede tomar 5-10 segundos)
    device.executeShellCommand(command, receiver, SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    // 2. Por cada app, ejecuta dumpsys (10 segundos timeout × N apps)
    val apps = packageNames.map { packageName ->
        createAppInfo(device, packageName)  // LLAMADA SÍNCRONA ⚠️
    }

    return apps
}

private fun createAppInfo(device: IDevice, packageName: String): AppInfo {
    // Ejecuta dumpsys package (toma 1-5 segundos por app)
    device.executeShellCommand(
        "dumpsys package $packageName",
        receiver,
        10,  // 10 SEGUNDOS de timeout
        TimeUnit.SECONDS
    )

    // Si hay 50 apps = 50 × 5 segundos = 4 MINUTOS ⚠️
}
```

**Impacto**:
- Cuando usuario hace click en "Refresh Apps" → **Android Studio se CONGELA**
- Con 50 apps instaladas = ~4 minutos de espera
- Usuario no puede hacer nada mientras tanto
- Da la impresión de que el IDE crasheó

### Solución Propuesta

**Suspending Functions con Coroutines + Parallel Processing**:

```kotlin
class AppManager(project: Project) {

    private val managerScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.IO +
        CoroutineName("AppManager")
    )

    // Cache de apps por device (válido por 5 minutos)
    private val appsCacheMap = ConcurrentHashMap<String, CachedAppList>()
    private val CACHE_VALIDITY_MS = 5 * 60 * 1000  // 5 minutos

    data class CachedAppList(
        val apps: List<AppInfo>,
        val timestamp: Long
    )

    /**
     * Get installed apps - suspending function.
     */
    suspend fun getInstalledAppsAsync(
        serialNumber: String,
        includeSystem: Boolean = false
    ): List<AppInfo> = withContext(Dispatchers.IO) {

        // Check cache first
        val cached = appsCacheMap[serialNumber]
        val now = System.currentTimeMillis()

        if (cached != null && (now - cached.timestamp) < CACHE_VALIDITY_MS) {
            logger.info("📦 Using cached app list for $serialNumber")
            return@withContext cached.apps
        }

        logger.info("🔄 Fetching apps for $serialNumber...")

        val device = getDevice(serialNumber) ?: return@withContext emptyList()

        // Execute shell command (I/O bound, usa Dispatchers.IO)
        val receiver = EmulatorManager.CollectingOutputReceiver()
        val command = if (includeSystem) "pm list packages" else "pm list packages -3"

        device.executeShellCommand(command, receiver, 30, TimeUnit.SECONDS)

        val packageNames = receiver.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }

        logger.info("Found ${packageNames.size} packages")

        // Procesar apps EN PARALELO con async ⭐
        val apps = packageNames.map { packageName ->
            async {  // Lanza coroutine paralela por cada app
                createAppInfoOptimized(device, packageName)
            }
        }.awaitAll()  // Espera a que TODAS terminen en paralelo

        // Update cache
        appsCacheMap[serialNumber] = CachedAppList(apps, now)

        logger.info("✅ Fetched ${apps.size} apps successfully")

        apps
    }

    private suspend fun createAppInfoOptimized(
        device: IDevice,
        packageName: String
    ): AppInfo = withContext(Dispatchers.IO) {
        try {
            // Solo obtener UID (más rápido que dumpsys completo)
            val uid = getAppUid(device, packageName)

            AppInfo(
                packageName = packageName,
                appName = null,
                versionName = null,
                versionCode = null,
                isSystemApp = false,
                uid = uid
            )
        } catch (e: Exception) {
            logger.debug("Failed to get details for $packageName: ${e.message}")
            AppInfo(packageName, null, null, null, false, null)
        }
    }

    fun invalidateCache(serialNumber: String) {
        appsCacheMap.remove(serialNumber)
        logger.debug("Cache invalidated for $serialNumber")
    }

    fun clearAllCaches() {
        appsCacheMap.clear()
        logger.debug("All app caches cleared")
    }
}
```

**InspectorPanel con Coroutines + Progress Flow**:

```kotlin
class InspectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val panelScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        CoroutineName("InspectorPanel")
    )

    private val refreshProgressLabel = JLabel("")

    private fun refreshApps() {
        val emulator = selectedEmulator ?: return

        refreshAppsButton.isEnabled = false
        refreshProgressLabel.text = "Loading..."

        // Lanzar coroutine
        panelScope.launch {
            val apps = try {
                appManager.getInstalledAppsAsync(
                    serialNumber = emulator.serialNumber,
                    includeSystem = false
                )
            } catch (e: Exception) {
                logger.error("Failed to fetch apps", e)
                emptyList()
            }

            // Actualizar UI en EDT
            withContext(Dispatchers.EDT) {
                appComboBox.removeAllItems()
                apps.forEach { app -> appComboBox.addItem(app) }

                if (apps.isNotEmpty()) {
                    appComboBox.selectedIndex = 0
                }

                refreshAppsButton.isEnabled = true
                refreshProgressLabel.text = "${apps.size} apps"

                logger.info("✅ Apps refreshed: ${apps.size} found")
            }
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        panelScope.cancel()  // Cleanup automático
    }
}
```

**Beneficios de Coroutines**:
- ✅ **Parallelization automática**: `async {}.awaitAll()` procesa todas las apps en paralelo
- ✅ **Non-blocking**: UI responsive mientras carga
- ✅ **Dispatchers.IO**: Optimizado para operaciones I/O (ADB shell commands)
- ✅ **Structured concurrency**: Cancelación automática si se cierra panel
- ✅ **Suspending functions**: Código secuencial que se ejecuta async
- ✅ **Cancelable**: El usuario puede cerrar el panel y todo se cancela limpiamente

**Nota sobre paralelización**:
Con 50 apps, en lugar de 50 × 5seg = 250seg secuenciales, se ejecutan en paralelo:
- Tiempo total ≈ 5-10 segundos (tiempo del más lento)
- **25x más rápido** que versión secuencial

**Esfuerzo**: ~4 horas
**Prioridad**: 🔴 CRÍTICA

---

## 🟡 PROBLEMA 6: Mockk Rules Matching - Búsqueda sin Índice

### Ubicación
**Archivo**: `MockkRulesStore.kt:299-350`

### Problema Actual
```kotlin
fun findMatchingRuleObject(method: String, host: String, path: String, queryParams: Map<String, String>): MockkRule? {
    // Itera sobre TODAS las reglas en CADA request ⚠️
    for ((index, rule) in rules.withIndex()) {
        if (!rule.enabled) continue

        val ruleCollection = collections[rule.collectionId]
        if (ruleCollection == null || !ruleCollection.enabled) continue

        // Match method
        if (!rule.method.equals(method, ignoreCase = true)) continue

        // Evaluate match (incluye regex evaluation)
        val matches = matchesStructuredQuiet(rule, host, path, queryParams)

        if (matches) return rule
    }

    return null
}
```

**Impacto**:
- Con 100 reglas + 100 requests/segundo = 10,000 iteraciones/segundo
- Evaluación de regex en cada iteración
- Complejidad O(n) donde n = número de reglas
- Sin caching de resultados

### Solución Propuesta

**Implementar Index Multi-Level + Caching**:

```kotlin
class MockkRulesStore(project: Project) : PersistentStateComponent<MockkRulesStore.State> {

    // Índice por método + host para lookup rápido
    private val ruleIndex = ConcurrentHashMap<String, MutableList<MockkRule>>()

    // Cache de matches recientes (evitar re-evaluar mismas URLs)
    private val matchCache = object : LinkedHashMap<String, MockkRule?>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MockkRule?>?): Boolean {
            return size > 100  // Mantener solo últimos 100 matches
        }
    }

    /**
     * Add rule and update index.
     */
    fun addRule(/* ... */): MockkRule {
        val rule = MockkRule(/* ... */)
        rules.add(rule)

        // Update index
        updateIndexForRule(rule)

        // Invalidate cache
        matchCache.clear()

        logger.info("➕ Added mock rule: $name to collection: $collectionId")
        ruleAddedListeners.forEach { it(rule) }

        return rule
    }

    /**
     * Remove rule and update index.
     */
    fun removeRule(rule: MockkRule) {
        if (rules.remove(rule)) {
            removeFromIndex(rule)
            matchCache.clear()
            logger.info("➖ Removed mock rule: ${rule.name}")
            ruleRemovedListeners.forEach { it(rule) }
        }
    }

    /**
     * Build/rebuild complete index.
     */
    private fun rebuildIndex() {
        ruleIndex.clear()
        rules.forEach { rule ->
            updateIndexForRule(rule)
        }
        logger.debug("🔨 Rebuilt rule index: ${ruleIndex.size} entries")
    }

    /**
     * Add rule to index.
     */
    private fun updateIndexForRule(rule: MockkRule) {
        val key = buildIndexKey(rule.method, rule.host)
        ruleIndex.getOrPut(key) { mutableListOf() }.add(rule)
    }

    /**
     * Remove rule from index.
     */
    private fun removeFromIndex(rule: MockkRule) {
        val key = buildIndexKey(rule.method, rule.host)
        ruleIndex[key]?.remove(rule)
    }

    /**
     * Build index key from method and host.
     */
    private fun buildIndexKey(method: String, host: String): String {
        return "${method.uppercase()}:${host.lowercase()}"
    }

    /**
     * Find matching rule using index - OPTIMIZED.
     */
    fun findMatchingRuleObject(
        method: String,
        host: String,
        path: String,
        queryParams: Map<String, String>
    ): MockkRule? {
        // Check cache first
        val cacheKey = "$method:$host:$path:${queryParams.hashCode()}"
        val cached = matchCache[cacheKey]
        if (cached != null) {
            logger.debug("✅ Cache hit for $cacheKey")
            return cached
        }

        logger.debug("🔍 Looking for match:")
        logger.debug("   Method: $method, Host: $host, Path: $path")

        // Get enabled collections
        val enabledCollections = collections.values.filter { it.enabled }
        if (enabledCollections.isEmpty()) {
            logger.debug("⚠️ No enabled collections")
            return null
        }

        // Use index to get candidate rules (only rules matching method:host)
        val indexKey = buildIndexKey(method, host)
        val candidateRules = ruleIndex[indexKey] ?: emptyList()

        logger.debug("📋 Index lookup: found ${candidateRules.size} candidate rules for $indexKey")

        // Iterate only over candidate rules (much smaller set)
        for (rule in candidateRules) {
            // Skip if rule not enabled
            if (!rule.enabled) continue

            // Skip if collection disabled
            val ruleCollection = collections[rule.collectionId]
            if (ruleCollection == null || !ruleCollection.enabled) continue

            // Match path and query params
            val matches = matchesStructuredQuiet(rule, host, path, queryParams)

            if (matches) {
                logger.debug("✅ MATCHED: ${rule.name}")

                // Cache result
                matchCache[cacheKey] = rule

                return rule
            }
        }

        logger.debug("❌ No matching rule found (checked ${candidateRules.size} candidates)")

        // Cache negative result too
        matchCache[cacheKey] = null

        return null
    }

    /**
     * Pre-compile regex patterns for faster matching.
     */
    data class MockkRule(
        // ... existing fields ...

        // Cached compiled regex patterns
        @Transient
        var compiledPathPattern: Regex? = null,
        @Transient
        var compiledQueryPatterns: Map<String, Regex>? = null
    ) {
        /**
         * Pre-compile regex patterns for this rule.
         */
        fun compilePatterns() {
            // Compile path regex if needed
            if (path.contains(".*") || path.contains("+") || path.contains("?")) {
                compiledPathPattern = try {
                    Regex(path)
                } catch (e: Exception) {
                    null
                }
            }

            // Compile query param regex patterns
            val regexParams = queryParams.filter { it.matchType == MatchType.REGEX }
            if (regexParams.isNotEmpty()) {
                compiledQueryPatterns = regexParams.associate { param ->
                    param.key to try {
                        Regex(param.value)
                    } catch (e: Exception) {
                        Regex(".*")  // Fallback
                    }
                }
            }
        }
    }

    override fun loadState(state: State) {
        collections.clear()
        rules.clear()

        // Load collections
        state.collections.forEach { collection ->
            collections[collection.id] = collection
        }

        // Load rules
        rules.addAll(state.rules)

        // Pre-compile regex patterns
        rules.forEach { it.compilePatterns() }

        // Build index
        rebuildIndex()

        // Migrate old rules
        migrateOldRulesToDefaultCollection()

        logger.info("📚 Loaded ${collections.size} collection(s) and ${rules.size} mock rule(s) from storage")
    }
}
```

**Optimizar evaluación de match con patterns pre-compilados**:

```kotlin
private fun matchesStructuredQuiet(rule: MockkRule, host: String, path: String, queryParams: Map<String, String>): Boolean {
    // 1. Match host (case-insensitive)
    if (!rule.host.equals(host, ignoreCase = true)) {
        return false
    }

    // 2. Match path (usar regex pre-compilado si existe)
    if (rule.compiledPathPattern != null) {
        if (!rule.compiledPathPattern!!.matches(path)) {
            return false
        }
    } else {
        // Exact match
        if (rule.path != path) {
            return false
        }
    }

    // 3. Match query params
    for (ruleParam in rule.queryParams) {
        if (ruleParam.required) {
            val actualValue = queryParams[ruleParam.key]
            if (actualValue == null) {
                return false
            }

            // Check value based on match type
            when (ruleParam.matchType) {
                MatchType.EXACT -> {
                    if (ruleParam.value != actualValue) {
                        return false
                    }
                }
                MatchType.WILDCARD -> {
                    // Accept any value
                }
                MatchType.REGEX -> {
                    // Use pre-compiled pattern
                    val pattern = rule.compiledQueryPatterns?.get(ruleParam.key)
                    if (pattern != null && !pattern.matches(actualValue)) {
                        return false
                    } else if (pattern == null) {
                        // Fallback to runtime compilation
                        try {
                            if (!Regex(ruleParam.value).matches(actualValue)) {
                                return false
                            }
                        } catch (_: Exception) {
                            return false
                        }
                    }
                }
            }
        }
    }

    return true
}
```

**Beneficios**:
- ✅ Búsqueda O(1) en lugar de O(n) con índice
- ✅ Cache reduce evaluaciones repetidas
- ✅ Regex pre-compilados más rápidos
- ✅ Maneja 1000s de reglas sin degradación

**Esfuerzo**: ~4 horas
**Prioridad**: 🟡 ALTA (no crítica pero importante)

---

## 🟡 PROBLEMA 7: Logging Excesivo

### Ubicación
**Archivo**: `MockkHttpLogger.kt:136`, `GlobalOkHttpInterceptorServer.kt`

### Problema Actual
- Cada request genera 5-10 líneas de log
- 100 requests/segundo = 500-1000 líneas de log/segundo
- Logger mantiene 1000 entries en memoria
- Listeners invocados en cada log entry
- Log Panel UI se actualiza constantemente

### Solución Propuesta

**Implementar Log Levels + Conditional Logging + Sampling**:

```kotlin
class MockkHttpLogger {

    // Configuración de niveles
    enum class LogLevel(val priority: Int) {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3)
    }

    companion object {
        // Nivel mínimo para loggear (configurable en settings)
        @Volatile
        var minimumLevel: LogLevel = LogLevel.INFO  // Solo INFO, WARN, ERROR por defecto

        // Límite de entries en memoria
        private const val MAX_LOG_ENTRIES = 500  // Reducir de 1000 a 500

        // Sampling: solo loggear 1 de cada N requests en DEBUG
        private const val DEBUG_SAMPLE_RATE = 10  // Solo 1 de cada 10 requests
        @Volatile
        private var debugSampleCounter = 0
    }

    fun debug(message: String) {
        // Skip si nivel es más alto que DEBUG
        if (minimumLevel.priority > LogLevel.DEBUG.priority) {
            return
        }

        // Sampling para reducir volumen
        debugSampleCounter++
        if (debugSampleCounter % DEBUG_SAMPLE_RATE != 0) {
            return  // Skip este log
        }

        log(LogLevel.DEBUG, message)
        platformLogger.debug(message)
    }

    fun info(message: String) {
        if (minimumLevel.priority > LogLevel.INFO.priority) {
            return
        }
        log(LogLevel.INFO, message)
        platformLogger.info(message)
    }

    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timestamp = getCurrentTimestamp(),
            level = level,
            message = message,
            throwable = throwable
        )

        logEntries.add(entry)

        // Limit log entries (reducido a 500)
        if (logEntries.size > MAX_LOG_ENTRIES) {
            // Remover los primeros 100 entries para hacer espacio
            repeat(100) {
                if (logEntries.isNotEmpty()) {
                    logEntries.removeAt(0)
                }
            }
        }

        notifyListeners(entry)
    }

    private fun notifyListeners(entry: LogEntry) {
        // Solo notificar si hay listeners registrados
        if (listeners.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onLogAdded(entry) }
        }
    }
}
```

**Reducir logging verboso en GlobalServer**:

```kotlin
class GlobalOkHttpInterceptorServer {

    private fun handleClient(socket: Socket) {
        socket.use { clientSocket ->
            try {
                // ... código existente ...

                // ANTES: 5 líneas de log por request
                // logger.info("🔴 INTERCEPTED: ${flowData.request.method} ${flowData.request.url}")
                // logger.info("   📦 Package: ${flowData.packageName ?: "unknown"}")
                // logger.info("   🎯 Project hint: ${flowData.projectId ?: "none"}")
                // ...

                // DESPUÉS: 1 línea concisa solo en DEBUG
                logger.debug("${flowData.request.method} ${flowData.request.url} -> ${targetProject?.projectName ?: "no project"}")

                // Route flow to appropriate project(s)
                val response = routeFlow(flowData)

                val responseJson = gson.toJson(response)
                writer.println(responseJson)

                // logger.debug("✅ Response sent")  // ELIMINAR - demasiado verboso

            } catch (e: Exception) {
                logger.error("Error handling client", e)  // Solo errores
            }
        }
    }
}
```

**Agregar UI Setting para controlar Log Level**:

```kotlin
// En Settings/Preferences panel
class MockkHttpSettingsPanel : JPanel() {
    private val logLevelComboBox = ComboBox(arrayOf(
        "DEBUG (verbose)",
        "INFO (default)",
        "WARN (minimal)",
        "ERROR (only errors)"
    ))

    fun apply() {
        val selectedLevel = when (logLevelComboBox.selectedIndex) {
            0 -> MockkHttpLogger.LogLevel.DEBUG
            1 -> MockkHttpLogger.LogLevel.INFO
            2 -> MockkHttpLogger.LogLevel.WARN
            3 -> MockkHttpLogger.LogLevel.ERROR
            else -> MockkHttpLogger.LogLevel.INFO
        }

        MockkHttpLogger.minimumLevel = selectedLevel
    }
}
```

**Beneficios**:
- ✅ Reduce logging en 90% (con INFO level)
- ✅ Menor presión en memoria y UI
- ✅ Usuario puede activar DEBUG cuando necesita troubleshooting
- ✅ Sampling evita spam en logs

**Esfuerzo**: ~2 horas
**Prioridad**: 🟡 MEDIA

---

## 🟡 PROBLEMA 8: Android Interceptor - Thread Creation sin Pool

### Ubicación
**Archivo**: `MockkHttpInterceptor.kt:266-283`

### Problema Actual
```kotlin
private fun sendToPluginAsync(
    request: Request,
    response: Response,
    duration: Long
) {
    Thread {  // Crea thread nuevo por CADA request async ⚠️
        try {
            val socket = Socket(pluginHost, pluginPort)
            // ...
        } catch (e: Exception) {
            Log.e(TAG, "Error sending flow async", e)
        }
    }.start()
}
```

**Impacto**:
- En Recording Mode (sin debug), cada request crea un thread
- 100 requests = 100 threads creados en la app Android
- Consume recursos de la app
- No hay límite ni reuso de threads

### Solución Propuesta

**Usar Kotlin Coroutines en Android Interceptor**:

```kotlin
class MockkHttpInterceptor @JvmOverloads constructor(
    context: Context? = null,
    private val pluginHost: String = "10.0.2.2",
    private val pluginPort: Int = 9876
) : Interceptor {

    companion object {
        private const val TAG = "MockkHttpInterceptor"

        // CoroutineScope para async operations (compartido)
        private val asyncScope = CoroutineScope(
            SupervisorJob() +
            Dispatchers.IO +
            CoroutineName("MockkHttp-Android-Async")
        )

        // Semaphore para limitar concurrencia
        private val sendSemaphore = Semaphore(5)  // Máximo 5 envíos concurrentes
        private val pendingCount = AtomicInteger(0)
        private const val MAX_PENDING = 100
    }

    private fun sendToPluginAsync(
        request: Request,
        response: Response,
        duration: Long
    ) {
        // Check if queue is full
        if (pendingCount.get() >= MAX_PENDING) {
            Log.w(TAG, "⚠️ Async queue full, skipping flow: ${request.url}")
            return
        }

        // Launch coroutine for async sending
        asyncScope.launch {
            pendingCount.incrementAndGet()

            try {
                // Acquire semaphore (limita a 5 concurrent sends)
                sendSemaphore.withPermit {
                    sendFlowToPlugin(request, response, duration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending flow async", e)
            } finally {
                pendingCount.decrementAndGet()
            }
        }
    }

    /**
     * Send flow to plugin - suspending function.
     */
    private suspend fun sendFlowToPlugin(
        request: Request,
        response: Response,
        duration: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(pluginHost, pluginPort)
            socket.soTimeout = CONNECTION_TIMEOUT_MS

            socket.use {
                val flowData = serializeFlow(request, response, duration)
                val json = gson.toJson(flowData) + "\n"

                it.getOutputStream().write(json.toByteArray())
                it.getOutputStream().flush()

                Log.d(TAG, "Sent flow to plugin (async): ${request.method} ${request.url}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send flow to plugin: ${e.message}")
        }
    }
}
```

**Extensión para Semaphore.withPermit()** (si no está disponible):

```kotlin
// Agregar en archivo utils
suspend inline fun <T> Semaphore.withPermit(action: () -> T): T {
    acquire()
    try {
        return action()
    } finally {
        release()
    }
}
```

**Beneficios de Coroutines en Android**:
- ✅ **Lightweight**: 100 coroutines ≈ 10KB vs 5 threads ≈ 5MB
- ✅ **Semaphore**: Control de concurrencia más elegante que ExecutorService
- ✅ **Dispatchers.IO**: Pool optimizado para I/O operations
- ✅ **Non-blocking**: Socket I/O no bloquea threads
- ✅ **Structured concurrency**: Scope compartido, fácil de cancelar
- ✅ **Backpressure**: Límite de pending + semaphore

**Nota**: Coroutines ya está disponible en Android (parte de Kotlin stdlib), no requiere dependencia adicional en apps modernas.

**Esfuerzo**: ~1.5 horas
**Prioridad**: 🟡 MEDIA

---

## 🔴 PROBLEMA 9: MockkHttpInterceptor - Hot Path Optimizations (Android)

### Ubicación
**Archivo**: `android-library/src/main/kotlin/.../MockkHttpInterceptor.kt`

### Contexto
El interceptor de Android es el **punto de entrada** de todas las requests HTTP de la app. Cualquier ineficiencia aquí se multiplica por el número de requests, causando:
- Latencia adicional en todas las requests HTTP
- Consumo excesivo de memoria y CPU en la app
- Degradación de experiencia de usuario

### Sub-problema 9.1: BuildConfig Reflection en Hot Path ⚠️ **CRÍTICO**

#### Ubicación
**Líneas**: 90-104

#### Problema Actual
```kotlin
override fun intercept(chain: Interceptor.Chain): Response {
    // SECURITY: Double-check we're not in a release build
    try {
        val buildConfigClass = Class.forName("${appContext?.packageName}.BuildConfig")
        val debugField = buildConfigClass.getDeclaredField("DEBUG")
        val isDebugBuild = debugField.getBoolean(null)

        if (!isDebugBuild) {
            Log.e(TAG, "❌ SECURITY: MockkHttpInterceptor in RELEASE build!")
            return chain.proceed(chain.request())
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not determine build type, assuming debug: ${e.message}")
    }
    // ... resto del intercept
}
```

**Impacto**:
- Esta reflection se ejecuta **EN CADA REQUEST HTTP** (hot path) ⚠️
- Reflection es muy costoso: `Class.forName()` + `getDeclaredField()` + `getBoolean()`
- Overhead: **1-5ms por request**
- Con 100 requests/segundo = **100-500ms de CPU** puro desperdiciado
- Con 1000 requests = **5 segundos de CPU** solo verificando build type

#### Solución Propuesta

```kotlin
class MockkHttpInterceptor @JvmOverloads constructor(
    context: Context? = null,
    private val pluginHost: String = "10.0.2.2",
    private val pluginPort: Int = 9876
) : Interceptor {

    companion object {
        private const val TAG = "MockkHttpInterceptor"

        // Cache del build type check (Double-Checked Locking)
        @Volatile
        private var isDebugBuildCached: Boolean? = null

        /**
         * Check if running in debug build.
         * Cached after first check to avoid repeated reflection.
         */
        private fun isDebugBuild(context: Context?): Boolean {
            // Fast path: check cache
            isDebugBuildCached?.let { return it }

            // Slow path: perform reflection once
            synchronized(this) {
                // Double-check after acquiring lock
                isDebugBuildCached?.let { return it }

                val result = try {
                    val buildConfigClass = Class.forName("${context?.packageName}.BuildConfig")
                    val debugField = buildConfigClass.getDeclaredField("DEBUG")
                    debugField.getBoolean(null)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not determine build type, assuming debug: ${e.message}")
                    true  // Fail-safe: assume debug
                }

                isDebugBuildCached = result
                Log.i(TAG, "Build type cached: ${if (result) "DEBUG" else "RELEASE"}")

                return result
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // SECURITY: Check cached build type (fast: ~0.01ms)
        if (!isDebugBuild(appContext)) {
            Log.e(TAG, "❌ SECURITY: MockkHttpInterceptor in RELEASE build!")
            return chain.proceed(chain.request())
        }

        // ... resto del intercept
    }
}
```

**Beneficios**:
- ✅ Reflection solo se ejecuta **UNA VEZ** al inicio
- ✅ Checks subsiguientes son **cache hit**: ~0.01ms
- ✅ Reduce overhead de **1-5ms → 0.01ms** por request
- ✅ Con 100 requests/segundo: ahorra **100-500ms de CPU**
- ✅ Thread-safe con Double-Checked Locking

**Esfuerzo**: ~1 hora
**Prioridad**: 🔴 CRÍTICA

---

### Sub-problema 9.2: Socket Creation Sin Pool ⚠️ **CRÍTICO**

#### Ubicación
**Líneas**: 175, 217, 268

#### Problema Actual
```kotlin
// Ping (línea 175)
Socket(pluginHost, pluginPort).use { socket ->
    socket.soTimeout = PING_TIMEOUT_MS
    // ...
}

// Debug mode (línea 217)
val socket = Socket(pluginHost, pluginPort)
socket.soTimeout = READ_TIMEOUT_MS
socket.use { /* ... */ }

// Recording mode (línea 268)
Thread {
    val socket = Socket(pluginHost, pluginPort)
    socket.soTimeout = CONNECTION_TIMEOUT_MS
    socket.use { /* ... */ }
}.start()
```

**Impacto**:
- Crea socket **TCP NUEVO** en cada operación
- TCP handshake overhead: **10-50ms por socket** (SYN, SYN-ACK, ACK)
- Con 100 requests/segundo = **100 sockets nuevos** = **1-5 segundos** de overhead
- No reutiliza conexiones (sin keep-alive)
- Agota file descriptors con alto volumen

#### Solución Propuesta

```kotlin
companion object {
    /**
     * Socket pool para reutilizar conexiones.
     */
    private object SocketPool {
        private val available = ConcurrentLinkedQueue<PooledSocket>()
        private const val MAX_POOL_SIZE = 5
        private const val SOCKET_MAX_AGE_MS = 30_000  // 30 segundos

        data class PooledSocket(
            val socket: Socket,
            val createdAt: Long = System.currentTimeMillis()
        ) {
            fun isStale(): Boolean {
                return System.currentTimeMillis() - createdAt > SOCKET_MAX_AGE_MS
            }

            fun isUsable(): Boolean {
                return !isStale() &&
                       socket.isConnected &&
                       !socket.isClosed &&
                       !socket.isInputShutdown &&
                       !socket.isOutputShutdown
            }
        }

        fun acquire(): Socket? {
            // Try to reuse existing socket
            while (true) {
                val pooled = available.poll() ?: break

                if (pooled.isUsable()) {
                    Log.v(TAG, "♻️ Reusing pooled socket")
                    return pooled.socket
                } else {
                    // Socket is stale or closed, discard
                    try {
                        pooled.socket.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }

            // Create new socket if pool is empty
            return try {
                Log.v(TAG, "🔌 Creating new socket")
                Socket().apply {
                    keepAlive = true  // Enable TCP keep-alive
                    tcpNoDelay = true  // Disable Nagle's algorithm for lower latency
                    connect(InetSocketAddress(pluginHost, pluginPort), CONNECTION_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create socket", e)
                null
            }
        }

        fun release(socket: Socket?) {
            socket?.let {
                try {
                    if (available.size < MAX_POOL_SIZE &&
                        it.isConnected &&
                        !it.isClosed) {

                        val pooled = PooledSocket(it)
                        if (pooled.isUsable()) {
                            available.offer(pooled)
                            Log.v(TAG, "💾 Socket returned to pool (size: ${available.size})")
                            return
                        }
                    }

                    // Pool full or socket not usable, close it
                    it.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing socket", e)
                }
            }
        }

        fun clear() {
            while (true) {
                val pooled = available.poll() ?: break
                try {
                    pooled.socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}

// Usar el pool en todas las operaciones
private fun isPluginConnected(): Boolean {
    // ... cache checks ...

    val connected = try {
        val socket = SocketPool.acquire() ?: return false

        try {
            socket.soTimeout = PING_TIMEOUT_MS
            socket.getOutputStream().write("PING\n".toByteArray())
            socket.getOutputStream().flush()

            val response = ByteArray(4)
            val read = socket.getInputStream().read(response)
            val success = read > 0 && String(response, 0, read).startsWith("PONG")

            if (success) {
                SocketPool.release(socket)  // Return to pool
            } else {
                socket.close()  // Don't reuse on error
            }

            success
        } catch (e: Exception) {
            socket.close()  // Don't reuse on error
            throw e
        }
    } catch (e: Exception) {
        Log.d(TAG, "⚠️ Plugin not reachable: ${e.message}")
        false
    }

    // ...
}

// Similar pattern for sendToPluginAndWait() and sendToPluginAsync()
```

**Beneficios**:
- ✅ Reutiliza sockets con **TCP keep-alive**
- ✅ Reduce overhead de **10-50ms → ~1ms** por request
- ✅ Con 100 requests/segundo: ahorra **1-5 segundos**
- ✅ Reduce file descriptors de 100 → 5
- ✅ Pool bounded evita leak de sockets

**Esfuerzo**: ~3 horas
**Prioridad**: 🔴 CRÍTICA

---

### Sub-problema 9.3: peekBody() para Responses Grandes ⚠️ **CRÍTICO**

#### Ubicación
**Líneas**: 305-322

#### Problema Actual
```kotlin
val responseBodyString = try {
    val contentLength = response.body?.contentLength() ?: 0
    val maxSize = if (contentLength > 0) {
        minOf(contentLength, 5 * 1024 * 1024)  // Max 5MB ⚠️
    } else {
        5 * 1024 * 1024  // Default 5MB ⚠️
    }

    Log.d(TAG, "📖 Reading response body: contentLength=$contentLength, maxSize=$maxSize")
    val body = response.peekBody(maxSize).string()  // Lee hasta 5MB en CADA request
    Log.d(TAG, "✅ Read ${body.length} chars from response body")
    body
} catch (e: Exception) {
    Log.w(TAG, "⚠️ Failed to read response body", e)
    ""
}
```

**Impacto**:
- `peekBody(5MB)` lee **hasta 5MB** en memoria por cada response
- Para imágenes/videos/PDFs es **MUY lento**: 100-500ms
- Con 10 requests de imágenes simultáneas = **50MB de memoria**
- Puede causar **OutOfMemoryError** en apps con alto tráfico
- Innecesario: El plugin NO necesita el body completo de una imagen de 5MB

#### Solución Propuesta

```kotlin
private fun serializeFlow(
    request: Request,
    response: Response,
    duration: Long
): FlowData {
    val responseBodyString = try {
        val contentType = response.body?.contentType()?.toString() ?: ""
        val contentLength = response.body?.contentLength() ?: 0

        // Skip body for binary content or large responses
        when {
            // Binary content: skip body
            contentType.contains("image/", ignoreCase = true) ||
            contentType.contains("video/", ignoreCase = true) ||
            contentType.contains("audio/", ignoreCase = true) ||
            contentType.contains("application/pdf", ignoreCase = true) ||
            contentType.contains("application/zip", ignoreCase = true) ||
            contentType.contains("application/octet-stream", ignoreCase = true) -> {
                Log.d(TAG, "📷 Skipping binary response body (${contentType}, ${formatBytes(contentLength)})")
                "[Binary content: $contentType, ${formatBytes(contentLength)}]"
            }

            // Large text responses: truncate
            contentLength > 100_000 -> {  // 100KB limit for text
                Log.d(TAG, "📦 Truncating large response (${formatBytes(contentLength)})")
                val body = response.peekBody(100_000).string()
                "$body\n\n... [Truncated: total ${formatBytes(contentLength)}]"
            }

            // Normal text response: read with reasonable limit
            else -> {
                val maxSize = if (contentLength > 0) {
                    minOf(contentLength, 1 * 1024 * 1024)  // Max 1MB (not 5MB)
                } else {
                    100_000  // Default 100KB (not 5MB)
                }

                response.peekBody(maxSize).string()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ Failed to read response body", e)
        ""
    }

    // ...
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes bytes"
    }
}
```

**Beneficios**:
- ✅ Reduce memoria de **5MB → 100KB** por request (50x menos)
- ✅ Reduce latencia de **100-500ms → 5-10ms** para imágenes
- ✅ Previene **OutOfMemoryError** con alto tráfico
- ✅ Mantiene funcionalidad para responses de texto (JSON, HTML, etc.)
- ✅ Mejora experiencia: la app no se ralentiza al capturar tráfico

**Esfuerzo**: ~2 horas
**Prioridad**: 🔴 CRÍTICA

---

### Sub-problema 9.4: No Batching en Recording Mode ⚠️ **IMPORTANTE**

#### Ubicación
**Líneas**: 266-282

#### Problema Actual
```kotlin
private fun sendToPluginAsync(
    request: Request,
    response: Response,
    duration: Long
) {
    Thread {  // Un thread NUEVO por request ⚠️
        try {
            val socket = Socket(pluginHost, pluginPort)  // Un socket NUEVO ⚠️
            socket.soTimeout = CONNECTION_TIMEOUT_MS

            socket.use {
                val flowData = serializeFlow(request, response, duration)
                val json = gson.toJson(flowData) + "\n"
                it.getOutputStream().write(json.toByteArray())
                it.getOutputStream().flush()

                Log.d(TAG, "Sent flow to plugin (async): ${request.method} ${request.url}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending flow async", e)
        }
    }.start()  // Lanza thread ⚠️
}
```

**Impacto**:
- En Recording mode, **cada request HTTP** crea:
  - 1 thread nuevo
  - 1 socket TCP nuevo
  - 1 serialización Gson
- Con 100 requests/segundo = **100 threads + 100 sockets** = Sobrecarga masiva
- Threads consumen stack memory (~1MB cada uno)
- Context switching reduce performance general de la app

#### Solución Propuesta (con Coroutines)

```kotlin
companion object {
    // Ya incluido en Problema #8 del documento principal
    // CoroutineScope para async operations
    private val asyncScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.IO +
        CoroutineName("MockkHttp-Android-Async")
    )

    // Channel para batching
    private val asyncChannel = Channel<FlowData>(capacity = 500)
    private var batchSenderStarted = false

    private fun startBatchSender() {
        if (batchSenderStarted) return

        synchronized(this) {
            if (batchSenderStarted) return
            batchSenderStarted = true

            // Batch sender coroutine
            asyncScope.launch {
                while (true) {
                    try {
                        // Collect batch (wait up to 500ms or 50 items)
                        val batch = mutableListOf<FlowData>()

                        // Wait for first item (blocking)
                        val first = withTimeoutOrNull(500) {
                            asyncChannel.receive()
                        } ?: continue

                        batch.add(first)

                        // Collect additional items (non-blocking)
                        repeat(49) {
                            val item = asyncChannel.tryReceive().getOrNull() ?: return@repeat
                            batch.add(item)
                        }

                        // Send batch
                        sendBatch(batch)

                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in batch sender", e)
                    }
                }
            }
        }
    }

    private suspend fun sendBatch(batch: List<FlowData>) {
        withContext(Dispatchers.IO) {
            try {
                val socket = SocketPool.acquire() ?: return@withContext

                try {
                    socket.soTimeout = CONNECTION_TIMEOUT_MS
                    val writer = socket.getOutputStream().bufferedWriter()

                    batch.forEach { flowData ->
                        val json = gson.toJson(flowData)
                        writer.write(json)
                        writer.write("\n")
                    }

                    writer.flush()
                    Log.d(TAG, "📦 Sent batch of ${batch.size} flows")

                    SocketPool.release(socket)
                } catch (e: Exception) {
                    socket.close()
                    throw e
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending batch", e)
            }
        }
    }
}

private fun sendToPluginAsync(
    request: Request,
    response: Response,
    duration: Long
) {
    startBatchSender()

    val flowData = serializeFlow(request, response, duration)

    // Offer to channel (non-blocking)
    val offered = asyncChannel.trySend(flowData).isSuccess
    if (!offered) {
        Log.w(TAG, "⚠️ Async channel full, dropping flow")
    }
}
```

**Beneficios**:
- ✅ Reduce de **100 threads → 1 coroutine** workers
- ✅ Reduce de **100 sockets → 2-3 sockets** reutilizados
- ✅ Batching: envía hasta 50 flows en un solo socket
- ✅ Non-blocking: no bloquea threads de OkHttp
- ✅ Backpressure: channel con capacidad limitada

**Esfuerzo**: ~4 horas (ya cubierto en Problema #8, aplicar aquí)
**Prioridad**: 🟡 ALTA (importante pero ya cubierto)

---

### Sub-problema 9.5: Headers.toMap() en Hot Path ⚠️ **MODERADO**

#### Ubicación
**Líneas**: 329, 334, 405-409

#### Problema Actual
```kotlin
return FlowData(
    // ...
    request = RequestData(
        // ...
        headers = request.headers.toMap(),  // Crea HashMap nuevo ⚠️
        // ...
    ),
    response = ResponseData(
        // ...
        headers = response.headers.toMap(),  // Crea HashMap nuevo ⚠️
        // ...
    ),
    // ...
)

// Extension function
private fun Headers.toMap(): Map<String, String> {
    return names().associateWith { name ->  // Allocates HashMap
        get(name) ?: ""
    }
}
```

**Impacto**:
- Se llama **2 veces por request** (request headers + response headers)
- Crea HashMap nuevo cada vez (allocation)
- Con headers grandes (10-20 headers) puede ser costoso
- Overhead: ~0.5ms por request

#### Solución Propuesta

```kotlin
/**
 * Convert OkHttp Headers to Map with pre-sized HashMap.
 */
private fun Headers.toMap(): Map<String, String> {
    if (size() == 0) return emptyMap()  // Fast path for empty headers

    // Pre-size HashMap to exact size (avoids resizing)
    val map = HashMap<String, String>(size())
    names().forEach { name ->
        map[name] = get(name) ?: ""
    }
    return map
}
```

**Beneficios**:
- ✅ Pre-sized HashMap evita resizing interno
- ✅ Fast path para headers vacíos
- ✅ Mejora ~0.5ms por request
- ✅ Menor presión en GC

**Esfuerzo**: ~0.5 horas
**Prioridad**: 🟢 BAJA (optimización menor pero fácil)

---

### Sub-problema 9.6: failedAttempts Sin Reset ⚠️ **BUG**

#### Ubicación
**Líneas**: 68, 161-164, 195

#### Problema Actual
```kotlin
companion object {
    @Volatile
    private var failedAttempts: Int = 0
    private const val MAX_FAILED_ATTEMPTS = 3
}

private fun isPluginConnected(): Boolean {
    // If we've failed too many times, stop trying (failsafe mode)
    if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
        Log.d(TAG, "⚠️ Plugin connection disabled (failsafe mode)")
        return false  // STUCK FOREVER ⚠️
    }

    // ... ping logic ...

    catch (e: Exception) {
        Log.d(TAG, "⚠️ Plugin not reachable: ${e.message}")
        failedAttempts++  // Increments on failure
        // But NEVER resets except on successful ping (line 186)
        Log.d(TAG, "Failed attempts: $failedAttempts / $MAX_FAILED_ATTEMPTS")
        false
    }

    // ...
}
```

**Impacto**:
- Si plugin se desconecta temporalmente, `failedAttempts` llega a 3
- Interceptor entra en "failsafe mode" **PERMANENTEMENTE**
- Incluso si el usuario reinicia el plugin, el interceptor NO se recupera
- Usuario tiene que **reiniciar la app** para volver a conectar
- Es un **BUG crítico de UX**

#### Solución Propuesta

```kotlin
companion object {
    @Volatile
    private var failedAttempts: Int = 0
    @Volatile
    private var lastFailedAttemptTime: Long = 0

    private const val MAX_FAILED_ATTEMPTS = 3
    private const val FAILSAFE_RESET_MS = 60_000  // Reset after 1 minute
}

private fun isPluginConnected(): Boolean {
    // Reset failsafe after timeout
    val now = System.currentTimeMillis()
    if (failedAttempts >= MAX_FAILED_ATTEMPTS &&
        now - lastFailedAttemptTime > FAILSAFE_RESET_MS) {
        Log.i(TAG, "🔄 Resetting failsafe mode after ${FAILSAFE_RESET_MS/1000}s timeout")
        failedAttempts = 0
    }

    // Check failsafe
    if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
        Log.d(TAG, "⚠️ Plugin connection disabled (failsafe mode)")
        return false
    }

    // ... cache check ...

    // Perform ping
    val connected = try {
        // ... ping logic ...
        success
    } catch (e: Exception) {
        Log.d(TAG, "⚠️ Plugin not reachable: ${e.message}")
        failedAttempts++
        lastFailedAttemptTime = now  // Track last failure ⭐
        Log.d(TAG, "Failed attempts: $failedAttempts / $MAX_FAILED_ATTEMPTS")
        false
    }

    // ...
}
```

**Beneficios**:
- ✅ Auto-recovery: interceptor se auto-recupera después de 1 minuto
- ✅ Usuario NO necesita reiniciar app
- ✅ Mejor UX: plugin puede reiniciarse sin afectar app
- ✅ Mantiene failsafe para protección, pero no permanente

**Esfuerzo**: ~0.5 horas
**Prioridad**: 🟡 ALTA (es un bug de UX)

---

## 📊 Resumen - Problema #9: MockkHttpInterceptor Optimizations

| Sub-problema | Impacto | Mejora | Esfuerzo |
|--------------|---------|--------|----------|
| 9.1: BuildConfig Reflection | 🔴 Crítico | 1-5ms → 0.01ms por request | 1h |
| 9.2: Socket Pool | 🔴 Crítico | 10-50ms → 1ms por request | 3h |
| 9.3: peekBody() Limit | 🔴 Crítico | 100-500ms → 5ms, 5MB → 100KB | 2h |
| 9.4: Batching (Coroutines) | 🟡 Alta | 100 sockets → 2-3, 100 threads → 1 | 4h |
| 9.5: Headers.toMap() | 🟢 Baja | ~0.5ms por request | 0.5h |
| 9.6: failedAttempts Reset | 🟡 Alta (bug) | Evita stuck permanente | 0.5h |

**Total Esfuerzo**: ~11 horas

**Impacto Combinado**:
- **Latencia por request**: 111-555ms → ~6ms (**18-92x más rápido**)
- **Memoria por request**: 5MB → 100KB (**50x menos memoria**)
- **Threads**: 100 → 1 (**100x menos threads**)
- **Sockets**: 100 → 2-3 con reuso (**33-50x menos sockets**)

**Prioridad General**: 🔴 **CRÍTICA** (especialmente 9.1, 9.2, 9.3)

---

## 📋 Resumen de Prioridades

### 🔴 **CRÍTICO** (Hacer primero - ~36 horas total)
1. ✅ **Coroutines + Channel en GlobalServer** → 3h
2. ✅ **Debug Mode Non-Blocking con CompletableDeferred** → 7h
3. ✅ **FlowStore Memory Management** → 4h
4. ✅ **UI Batching con Flow (reactive streams)** → 5h
5. ✅ **ADB Async Operations con parallel processing** → 4h
6. ✅ **Agregar dependencia kotlinx-coroutines** → 2h
7. ✅ **MockkHttpInterceptor - Sub-problemas 9.1, 9.2, 9.3** → 6h
8. ✅ **MockkHttpInterceptor - Sub-problemas 9.4, 9.5, 9.6** → 5h

### 🟡 **ALTA PRIORIDAD** (Hacer después - ~7.5 horas total)
9. ✅ **Mockk Rules Indexing** → 4h
10. ✅ **Logging Optimization** → 2h
11. ✅ **Android Interceptor con Coroutines** → 1.5h

### 🟢 **MEJORAS ADICIONALES** (Opcional - futuro)
- Virtual Scrolling para listas muy grandes
- Compresión de flows con GZIP
- Métricas de performance en UI
- "Light Mode" para apps con tráfico masivo

---

## 🎯 Estrategia de Implementación

### Pre-requisito: Agregar Dependencia de Coroutines
```kotlin
// build.gradle.kts
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0") // Para Dispatchers.EDT
}
```

### Fase 1 (Semana 1-2): Arreglos Críticos con Coroutines
**Orden recomendado**:
1. **Agregar dependencia de coroutines** (2h) - prerequisito
2. **Coroutines + Channel en GlobalServer** (3h) - base fundamental, elimina thread explosion
3. **FlowStore Memory Management** (4h) - previene OutOfMemory, independiente de coroutines
4. **UI Batching con Flow** (5h) - UI responsive, usa reactive streams
5. **ADB Async con parallel processing** (4h) - previene bloqueos de IDE, paraleliza operaciones
6. **Debug Mode Non-Blocking** (7h) - feature más compleja, usa CompletableDeferred

**Total Fase 1**: ~25 horas de trabajo

### Fase 2 (Semana 3): Optimizaciones
7. **Mockk Rules Indexing** (4h) - mejora matching performance
8. **Logging Optimization** (2h) - reduce overhead de logs
9. **Android Interceptor con Coroutines** (1.5h) - optimiza app Android

**Total Fase 2**: ~7.5 horas

### Fase 3: Testing Integral
- Crear app de prueba que genere 100+ requests/segundo
- Probar con múltiples proyectos abiertos simultáneamente
- Monitorear memoria y CPU durante carga alta
- Verificar que Debug Mode no bloquea con muchas requests
- Stress test: 500+ requests simultáneas
- Memory profiling: verificar que memoria se mantiene bajo 100MB

---

## 📊 Métricas de Éxito

**Antes de las optimizaciones**:
- ❌ 100 requests simultáneas → Plugin collapsa
- ❌ Debug Mode con 10+ requests → App Android se congela
- ❌ Refresh Apps → Android Studio bloqueado 2-5 minutos
- ❌ UI se congela con alto tráfico
- ❌ Memoria crece sin control

**Después de las optimizaciones**:
- ✅ 500+ requests simultáneas → Plugin responde correctamente
- ✅ Debug Mode con 50+ requests → Queuing funciona, app no se congela
- ✅ Refresh Apps → Background loading, IDE responsive
- ✅ UI fluida incluso con cientos de requests
- ✅ Memoria estable bajo 100MB

---

## 🛠️ Herramientas de Monitoreo (a agregar)

```kotlin
// Panel de estadísticas en InspectorPanel
class PerformanceStatsPanel : JPanel() {
    private val threadsLabel = JLabel("Threads: 0")
    private val memoryLabel = JLabel("Memory: 0 MB")
    private val requestsPerSecLabel = JLabel("Requests/s: 0")
    private val queueSizeLabel = JLabel("Queue: 0")

    init {
        layout = FlowLayout(FlowLayout.LEFT)
        add(threadsLabel)
        add(memoryLabel)
        add(requestsPerSecLabel)
        add(queueSizeLabel)

        // Update every second
        Timer(1000) {
            updateStats()
        }.apply { isRepeating = true }.start()
    }

    private fun updateStats() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        memoryLabel.text = "Memory: $usedMemory MB"
        // ... otros stats
    }
}
```

---

---

## 🔬 Comparativa: ExecutorService vs Kotlin Coroutines

### ¿Por qué Coroutines en lugar de ExecutorService?

| Aspecto | ExecutorService (Tradicional) | **Kotlin Coroutines** (Recomendado) |
|---------|-------------------------------|--------------------------------------|
| **Memoria** | 50 threads = ~50MB | 50 coroutines = ~500KB | ✅ 100x menos memoria
| **Escalabilidad** | ~1,000 threads máximo antes de colapso | 100,000+ coroutines sin problemas | ✅ Escala masivamente
| **Context Switching** | Alto overhead de CPU | Minimal (cooperativo, no preemptivo) | ✅ Más eficiente
| **Backpressure** | Manual (BlockingQueue) | Built-in (Channel, Flow) | ✅ Nativo
| **Cancelación** | Manual (interrupts, flags) | Structured concurrency (automática) | ✅ Segura y limpia
| **IntelliJ Integration** | Básica | Nativa (Dispatchers.EDT) | ✅ Primera clase
| **Reactive Streams** | Necesita RxJava/Reactor | Flow nativo | ✅ Integrado
| **Código** | Verboso (callbacks, try/finally) | Conciso (suspending functions) | ✅ Más legible
| **Debugging** | Complicado (stack traces mezcladas) | IntelliJ Coroutines Debugger | ✅ Mejor tooling
| **Timeout** | Manual (`Future.get(timeout)`) | Built-in (`withTimeout()`) | ✅ Simple
| **Parallel Processing** | Manual (`invokeAll()`) | `async {}.awaitAll()` | ✅ Declarativo
| **Esfuerzo migración** | ~20 horas | ~25 horas | ⚠️ +5 horas

### Ventajas Clave de Coroutines

#### 1. **Lightweight y Escalabilidad**
```kotlin
// ExecutorService: Limitado por threads del OS
val executor = Executors.newFixedThreadPool(50)  // Máximo práctico
// 50 threads = ~50MB de memoria

// Coroutines: Limitado solo por memoria
repeat(100_000) {
    launch { /* task */ }  // 100,000 coroutines = ~10MB
}
```

#### 2. **Backpressure Nativo**
```kotlin
// ExecutorService: Manual
val queue = LinkedBlockingQueue<Task>(500)
if (!queue.offer(task)) {
    // Rechazar manualmente
}

// Coroutines: Built-in
val channel = Channel<Task>(capacity = 500)
channel.send(task)  // Suspende si está lleno (no bloquea thread!)
```

#### 3. **Structured Concurrency**
```kotlin
// ExecutorService: Cleanup manual
executor.shutdown()
if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
    executor.shutdownNow()
}

// Coroutines: Automático
scope.cancel()  // Cancela TODAS las coroutines inmediatamente
```

#### 4. **Reactive Streams Nativos**
```kotlin
// ExecutorService + RxJava (dependencia externa)
Observable.create<Flow> { emitter ->
    // ...
}.buffer(50)
 .debounce(300, TimeUnit.MILLISECONDS)
 .observeOn(AndroidSchedulers.mainThread())

// Coroutines: Flow nativo
flowStore.flowAdded
    .buffer(50)
    .debounce(300)
    .flowOn(Dispatchers.Main)
```

#### 5. **Parallel Processing Declarativo**
```kotlin
// ExecutorService: Verboso
val futures = packageNames.map { pkg ->
    executor.submit(Callable { processPackage(pkg) })
}
val results = futures.map { it.get() }  // Bloquea

// Coroutines: Elegante
val results = packageNames.map { pkg ->
    async { processPackage(pkg) }
}.awaitAll()  // No bloquea, suspende
```

### ¿Cuándo usar cada uno?

#### Usar ExecutorService si:
- ❌ No quieres agregar dependencia de coroutines
- ❌ El equipo no conoce coroutines
- ❌ Proyecto legacy con mucho código usando threads

#### Usar Coroutines si:
- ✅ Necesitas alta concurrencia (100s-1000s de tasks)
- ✅ Quieres código más limpio y mantenible
- ✅ Necesitas reactive streams (Flow)
- ✅ Trabajas en Kotlin (IntelliJ Platform)
- ✅ Necesitas backpressure y cancelación robusta
- ✅ **Es un proyecto nuevo o refactor** ← **Tu caso**

### Trade-off: Tiempo de Implementación

| Fase | ExecutorService | Coroutines | Diferencia |
|------|-----------------|------------|------------|
| Setup | 0h | +2h (agregar deps) | +2h |
| Problema #1 | 2h | 3h | +1h |
| Problema #2 | 6h | 7h | +1h |
| Problema #4 | 5h | 5h | 0h |
| Problema #5 | 3h | 4h | +1h |
| **Total** | **20h** | **25h** | **+5h** |

**Conclusión**: +5 horas de inversión inicial para una arquitectura **mucho mejor** a largo plazo.

### Recomendación Final

**Usa Coroutines** porque:

1. ✅ **Performance superior**: Menos memoria, más escalable
2. ✅ **Código más limpio**: Suspending functions vs callbacks
3. ✅ **Mejor integración**: IntelliJ Platform está migrando a coroutines
4. ✅ **Futureproof**: Coroutines es el estándar en Kotlin ecosystem
5. ✅ **+5 horas iniciales valen la pena**: Evita refactor futuro

El plugin funcionará **mejor con Coroutines** y será **más fácil de mantener** a largo plazo.

---

**Documento creado**: 2025-01-08
**Última actualización**: 2025-01-08
**Estado**: Pendiente de implementación
**Decisión arquitectónica**: Usar Kotlin Coroutines en lugar de ExecutorService tradicional
