# iOS Support Spike — guía de prueba local (Flutter en iOS)

Rama: `spike/ios-flutter-simulator` · Plugin `1.6.0` · mockk_http `1.6.0`

## Qué añade este spike

| Pieza | Cambio |
|---|---|
| **mockk_http (Flutter)** | Host resuelto por plataforma: Android emu `10.0.2.2` · iOS Simulator `127.0.0.1` (cero config) · iOS físico `host:` manual. Bundle id auto-detectado (`__CFBundleIdentifier`). Marker file en el contenedor del simulador. |
| **Plugin IDE** | Simuladores iOS booteados y dispositivos físicos iOS en el selector de Device (`simctl`/`devicectl`), listado de apps por plataforma, botón 🔄 de refresh de devices. Sin `adb reverse` para iOS (no hace falta). |

El servidor, el protocolo, los modos (Recording/Debug/Mockk) y toda la UI son los mismos — un flow de iOS entra por el mismo socket 9876.

## Cómo probar (iOS Simulator — el camino feliz)

1. **Instala el plugin del spike** en Android Studio/IntelliJ:
   - Zip generado en `build/distributions/MockkHttp-1.6.0.zip`
   - `Settings → Plugins → ⚙️ → Install Plugin from Disk…` → selecciona el zip → Restart.

2. **Apunta tu app Flutter al paquete local** (en el `pubspec.yaml` de tu app):
   ```yaml
   dependencies:
     mockk_http:
       path: /Users/sergiy/IdeaProjects/MockkNetworkInspector/MockkHttp/flutter-package
   ```
   Y en tu `main()` (igual que en Android):
   ```dart
   void main() {
     MockkHttp.init();          // detecta iOS Simulator y usa 127.0.0.1
     runApp(MyApp());
   }
   // (si usas dio: dio.interceptors.add(MockkHttpDioInterceptor());)
   ```

3. **Arranca un simulador y tu app**:
   ```bash
   open -a Simulator          # o desde Xcode/Android Studio
   flutter run                # target: el simulador iOS
   ```
   En la consola de Flutter verás el banner `MockkHttp v1.6.0 (iOS Simulator) … Host: 127.0.0.1:9876`.

4. **En el plugin**: pestaña MockkHttp → pulsa 🔄 junto a "Device" → aparece `🍎 iPhone … (iOS 18.x)` → selecciona tu app (🎭 si ya se anunció) → **Start** en Recording/Debug/Mockk. Las llamadas del simulador aparecen en el Inspector como las de Android.

## Dispositivo iOS físico (best effort)

1. La app debe conocer la IP del Mac (misma red Wi-Fi):
   ```dart
   MockkHttp.init(host: '192.168.1.50');   // IP LAN de tu Mac
   ```
2. Añade a `ios/Runner/Info.plist` (iOS 14+ pide permiso de red local la primera vez):
   ```xml
   <key>NSLocalNetworkUsageDescription</key>
   <string>MockkHttp debug traffic inspection</string>
   ```
3. El dispositivo aparece como `🍏 …` si está conectado por USB/emparejado (Xcode 15+). Si `devicectl` no puede listar sus apps, se usan las apps que ya hicieron PING al plugin (arranca la app primero y pulsa refresh de apps).

## Limitaciones conocidas del spike
- Flutter iOS intercepta lo mismo que en Android: `HttpClient` de `dart:io` (y `package:http`) vía HttpOverrides + dio vía interceptor. No captura WebSockets ni plugins nativos que no pasen por ahí.
- Apps **nativas** iOS (Swift/URLSession) NO están cubiertas — eso requeriría el paquete Swift/URLProtocol (fase posterior, ver informe de viabilidad).
- El listado de apps del simulador muestra todas las apps de usuario (🎭 = detectada con MockkHttp tras su primer arranque o PING).
- `__CFBundleIdentifier`/`SIMULATOR_*` env vars: verificadas como mecanismo estándar, pero valida en tu app que el banner muestra el bundle id correcto; si no, pasa `packageName:` explícito.

## Troubleshooting (aprendido en las pruebas reales)

**Síntoma: la app y el simulador se detectan, pero no llegan llamadas.**

1. **`packageName:` hardcodeado con el id de Android.** El package de Android
   (`com.foo.my_app`) y el bundle id de iOS (`com.foo.myApp`) suelen diferir. Si pasas
   `MockkHttp.init(packageName: '<android>')`, en iOS la app se anuncia con el id
   equivocado y el plugin **descarta sus flows por el filtro estricto** — y ese descarte
   solo se loguea en `idea.log`, no en el panel de Logs del plugin. Solución: no pases
   `packageName` — la autodetección funciona en ambas plataformas (Android via
   `/proc/self/cmdline`, iOS via `__CFBundleIdentifier`).
2. **Orden de arranque** (solo versiones < cooldown fix): si la app arrancaba antes de
   pulsar Start, dejaba de reintentar para siempre. Desde `39a6af4` reintenta cada 15s.
3. **Verifica la ruta de red desde el Mac**: `printf 'PING\n' | nc -w 2 127.0.0.1 9876`
   debe responder `PONG` con una sesión iniciada (Start) en el plugin.
4. **Verifica qué código lleva la app instalada** (sin fiarte del pubspec):
   ```bash
   APP=$(xcrun simctl get_app_container booted <bundle-id> app)
   grep -ac "1.6.0-dev" "$APP"/Frameworks/App.framework/flutter_assets/kernel_blob.bin
   ```

## Regenerar el zip del plugin
```bash
./gradlew buildPlugin   # → build/distributions/MockkHttp-1.6.0.zip
```
