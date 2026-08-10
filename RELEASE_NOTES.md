# FrameCast — Release Notes

---

# ESPAÑOL

## v0.0.14 — primera alpha pública (10 de agosto de 2026)

FrameCast (antes "PhoneCam") convierte tu Android en una webcam para PC por
USB o Wi-Fi: H.264 hasta 4K60, audio AAC sincronizado y salida como cámara
virtual para OBS, Zoom, Meet o Discord.

### Novedades

- **Emisión de pantalla**: un menú arriba a la derecha permite elegir entre
  Cámara y Pantalla. En modo Pantalla la emisión continúa aunque salgas de
  la app (notificación permanente, como exige Android), y la app te avisa de
  que los límites aplicables son los de la pantalla — su resolución y su
  tasa de refresco — no los de la cámara. Verificado en dispositivo real:
  60 fps sostenidos de punta a punta, incluso con la app en segundo plano.
- **La fuente de OBS se crea sola**: ya no hay que añadir ni renombrar nada
  en OBS. El instalador (o la primera emisión con OBS abierto) crea una
  fuente `FrameCast` apuntando a la cámara virtual, y sigue sin tocar
  ninguna otra fuente de la escena.
- **El PC siempre listo**: el receiver arranca con el PC y se mantiene vivo
  solo, de modo que el móvil puede conectar desde el primer segundo — ya no
  hace falta abrir OBS para que los servicios existan.
- **Nueva identidad: FrameCast**, con icono y branding nuevos en app,
  instalador y receiver de PC.
- **Audio de extremo a extremo**: captura real del micrófono, AAC-LC,
  sincronización A/V en el PC y salida por cable de audio virtual.
- **Cadena de geometría medida en hardware**: rotación, composición y
  aspecto correctos en las cuatro orientaciones físicas, con la evidencia
  de cada caso registrada en dispositivo real.
- **Lienzo de OBS que sigue a la composición** (16:9, 4:3, 1:1, 9:16, 3:4)
  vía obs-websocket, tocando únicamente la fuente llamada `FrameCast`.
- **Conexión sin comandos**: USB auto-configura sus túneles al enchufar;
  Wi-Fi descubre el PC solo (tras un emparejamiento USB inicial).
- **Diagnóstico de cámara** (Ajustes → Dispositivo): informe Camera2
  completo exportable en JSON.
- **Backend experimental Camera2** (opcional, off por defecto): 4K60 real
  en dispositivos que lo soportan.
- Localización completa en 20 idiomas, incluida la nueva pantalla de
  diagnóstico.

### Mejoras

- Reconexión automática con retroceso exponencial ante cortes de red o USB.
- Backpressure de vídeo acotado: la latencia no se acumula en sesiones
  largas.
- El receiver sobrevive a desconexiones bruscas, medias conexiones y datos
  corruptos sin reiniciarse.
- Watchdog anticongelación de cámara con reinicio automático de sesión.

### Correcciones

- **Composiciones 9:16, 1:1 y 3:4 corregidas**: el vídeo llegaba a OBS
  girado, ampliado y desplazado. La causa era una rotación que la cámara ya
  traía aplicada y que el renderizador volvía a compensar; ahora esa
  rotación se **mide en cada frame** de la propia matriz de textura en vez
  de asumirse, y las cinco composiciones se han verificado frame a frame en
  dispositivo real.
- **La emisión de pantalla ya se puede detener desde fuera de la app.** La
  notificación —que en Android 13+ no llegaba a mostrarse porque nunca se
  pedía permiso— ahora aparece y lleva un botón **Detener**. Además, si la
  app se cierra en segundo plano durante una emisión, la proyección se
  libera en vez de quedarse compartiendo la pantalla sin dueño.
- **El vídeo ya no aparece desplazado ni desbordado en OBS.** Al cambiar de
  composición se redimensionaba el lienzo pero no se reencajaba la fuente,
  que se quedaba con la forma anterior. Además, el diálogo con OBS podía
  desincronizarse (sus eventos viajan por el mismo canal que las respuestas),
  lo que hacía que algunas lecturas devolvieran datos de otra petición.
- Las llamadas de control por Wi-Fi ahora se autentican con la clave del
  emparejamiento (antes fallaban con "PC no accesible" aunque el vídeo
  funcionara).
- El servidor del test de velocidad rechaza tamaños absurdos en lugar de
  intentar reservarlos en memoria.
- Corregida una fuga del encoder al entrar y salir de Ajustes repetidamente.
- El visor de Camera2 conserva su proporción en vez de estirarse.
- Versión visible en Ajustes actualizada y unificada con el build.

### Retirado en esta versión

- El interruptor de HDR y el selector de códec de vídeo: no tenían efecto
  real sobre el stream (y HDR podía corromper la salida en el PC). Volverán
  cuando exista una implementación completa.

### Limitaciones conocidas

- **60 fps depende del backend.** La ruta estándar (CameraX) entrega 30 fps
  en este dispositivo; los 60 fps reales llegan con la captura Camera2
  experimental (Ajustes → Vídeo) y solo en composiciones 16:9 y 4:3. Cuando
  no se pueden dar 60, la app ahora lo dice: la etiqueta de grabación muestra
  los fps que realmente se envían, y OBS recibe ese mismo número.
- **8K se captura en 8K y se entrega en 4K.** La cámara virtual de OBS no
  arranca a 7680×4320 —y un intento fallido la deja inutilizable para
  cualquier resolución hasta reiniciar OBS—, así que el fotograma sale a 4K
  reescalado por GPU desde el sensor completo.
- Códec fijo H.264 (HEVC automático por encima de 4K).
- Wi-Fi requiere emparejamiento USB inicial.
- Los anuncios de esta build son los de **prueba** de Google (no hay
  publicidad real todavía).
- Térmica no validada en sesiones muy largas a máxima resolución.
- El visor espeja la cámara frontal; el stream no.

### Requisitos

Windows 10/11 con Python 3.10+ y OBS 28+; Android 8.0+. VB-CABLE opcional
para el micrófono. USB requiere Depuración USB activada.

---

# ENGLISH

## v0.0.14 — first public alpha (August 10, 2026)

FrameCast (formerly "PhoneCam") turns your Android phone into a PC webcam
over USB or Wi-Fi: H.264 up to 4K60, synced AAC audio, and virtual-camera
output for OBS, Zoom, Meet or Discord.

### New

- **Screen casting**: a top-right menu switches between Camera and Screen.
  In Screen mode the stream keeps going even if you leave the app (with a
  permanent notification, as Android requires), and the app tells you that
  the applicable limits are the screen's — its resolution and refresh rate —
  not the camera's. Verified on a real device: a sustained 60 fps end to
  end, including with the app in the background.
- **The OBS source creates itself**: nothing to add or rename in OBS any
  more. The installer (or the first stream with OBS open) creates a
  `FrameCast` source pointed at the virtual camera, and still never touches
  any other source in the scene.
- **The PC is always ready**: the receiver starts with the PC and keeps
  itself alive, so the phone can connect from the very first second — OBS no
  longer needs to be open for the services to exist.
- **New identity: FrameCast**, with a new icon and branding across the app,
  the installer and the PC receiver.
- **End-to-end audio**: real microphone capture, AAC-LC, PC-side A/V sync
  and output through a virtual audio cable.
- **Hardware-measured video geometry**: correct rotation, composition and
  aspect in all four physical orientations, each case backed by on-device
  evidence.
- **OBS canvas that follows the composition** (16:9, 4:3, 1:1, 9:16, 3:4)
  via obs-websocket, only ever touching the source named `FrameCast`.
- **Zero-command connection**: USB sets up its own tunnels on plug-in;
  Wi-Fi discovers the PC by itself (after a one-time USB pairing).
- **Camera diagnostics** (Settings → Device): full Camera2 report,
  exportable as JSON.
- **Experimental Camera2 backend** (optional, off by default): true 4K60 on
  devices that support it.
- Full localization in 20 languages, including the new diagnostics screen.

### Improved

- Automatic reconnection with exponential backoff across network or USB
  drops.
- Bounded video backpressure: latency does not accumulate over long
  sessions.
- The receiver survives abrupt disconnects, half-open connections and
  corrupt data without restarting.
- Camera-freeze watchdog with automatic session restart.

### Fixed

- **9:16, 1:1 and 3:4 compositions fixed**: video reached OBS rotated,
  oversized and shifted. The cause was a rotation the camera already
  carried that the renderer compensated for again; that rotation is now
  **measured per frame** from the texture matrix itself instead of assumed,
  and all five compositions were verified frame by frame on a real device.
- **Screen casting can now be stopped from outside the app.** The
  notification — which on Android 13+ never appeared, because permission was
  never requested — now shows up and carries a **Stop** button. And if the
  app is destroyed in the background mid-cast, the projection is released
  instead of being left sharing the screen with no owner.
- **Video no longer appears shifted or oversized in OBS.** Changing the
  composition resized the canvas without re-fitting the source, which kept
  the previous shape. The dialogue with OBS could also desync — its events
  travel down the same channel as replies — so some reads returned another
  request's data.
- Wi-Fi control calls now authenticate with the pairing key (they used to
  fail with "PC not reachable" even while video worked).
- The speed-test server rejects absurd declared sizes instead of trying to
  allocate them.
- Fixed an encoder leak when entering and leaving Settings repeatedly.
- The Camera2 viewfinder keeps its aspect ratio instead of stretching.
- The version shown in Settings is updated and unified with the build.

### Removed in this release

- The HDR switch and the video-codec selector: they had no real effect on
  the stream (and HDR could corrupt the PC-side output). They will return
  once a complete implementation exists.

### Known limitations

- **60 fps depends on the backend.** The standard path (CameraX) delivers
  30 fps on this device; real 60 fps comes from the experimental Camera2
  capture (Settings → Video) and only in 16:9 and 4:3 compositions. When 60
  cannot be delivered the app now says so: the recording label shows the fps
  actually being sent, and OBS receives that same number.
- **8K is captured at 8K and delivered at 4K.** OBS's virtual camera cannot
  start at 7680×4320 — and a refused attempt leaves it unusable at any
  resolution until OBS is restarted — so the frame goes out as 4K, GPU-scaled
  from the full sensor.
- Fixed H.264 codec (HEVC automatically above 4K).
- Wi-Fi requires a one-time USB pairing.
- Ads in this build are Google's **test** ads (no real advertising yet).
- Thermals not validated on very long sessions at maximum resolution.
- The viewfinder mirrors the front camera; the stream does not.

### Requirements

Windows 10/11 with Python 3.10+ and OBS 28+; Android 8.0+. VB-CABLE is
optional, for the microphone. USB requires USB debugging enabled.
