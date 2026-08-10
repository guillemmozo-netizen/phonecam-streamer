# FrameCast

**v0.0.14 (alpha pública / public alpha)**

---

# ESPAÑOL

## Qué es FrameCast

FrameCast convierte tu móvil Android en una webcam para PC, por **USB** o por
**Wi-Fi**. El vídeo viaja en H.264 (hasta 4K60) con audio AAC sincronizado, y
aparece en tu PC como una cámara normal (vía OBS Virtual Camera) que puedes
usar en OBS, Zoom, Meet, Discord, etc.

Además de la cámara, FrameCast puede emitir la **pantalla del móvil**: con el
menú de arriba a la derecha eliges Cámara o Pantalla, y en modo Pantalla la
emisión continúa aunque salgas de la app (con su notificación permanente,
como exige Android). En este modo los límites que se aplican son los de la
pantalla (su resolución y su tasa de refresco), no los de la cámara — la
propia app te lo indica al activarlo.

- **Gratis**: 1080p60 con una marca de agua pequeña.
- **Pro temporal viendo un anuncio**: 4K60, sin marca de agua y sin anuncios
  durante 12 horas. Sin suscripción y sin paywall: durante la fase alpha/beta
  todas las funciones Pro se desbloquean así.

## Requisitos

**PC (Windows 10/11):**
- [Python 3.10 o superior](https://www.python.org/downloads/) — marca
  *"Add python.exe to PATH"* al instalarlo.
- [OBS Studio 28+](https://obsproject.com/) — aporta la cámara virtual y,
  opcionalmente, el ajuste automático del lienzo.
- Para USB: los [platform-tools de Android (adb)](https://developer.android.com/tools/releases/platform-tools)
  en el PATH o en la ruta estándar del SDK.
- Opcional, para usar el micrófono del móvil en videollamadas:
  [VB-CABLE](https://vb-audio.com/Cable/) (gratuito).

**Móvil:**
- Android 8.0 o superior, con cámara.
- Validado a fondo en Samsung Galaxy S23 Ultra; en otros dispositivos la app
  adapta resoluciones y lentes a lo que informe el hardware.

## Instalación

**En el PC:**
1. Extrae el ZIP completo en una carpeta (por ejemplo `C:\FrameCast`).
2. Haz doble clic en **`FrameCast Setup`**. Es silencioso: prepara un
   entorno privado de Python, activa el WebSocket de OBS y deja FrameCast
   **arrancando con el PC**, siempre listo para recibir al móvil al
   instante. Al terminar muestra un resumen.
3. Si Windows pregunta por el firewall la primera vez, permite el acceso en
   redes privadas (necesario para el modo Wi-Fi).

**En el móvil:**
1. Instala **FrameCast** desde **Google Play**.
2. En la app: Ajustes → Control del PC → **"Descargar el instalador de PC"**
   te lleva a este ZIP, por si aún no lo tienes en el ordenador.
3. Para USB: activa **Depuración USB** (Ajustes → Información del teléfono →
   toca 7 veces "Número de compilación" → Opciones de desarrollador →
   Depuración USB).

## Conexión y emparejamiento

**USB (recomendado):** conecta el móvil por cable y acepta el diálogo de
depuración USB. No hay que ejecutar nada más: el PC detecta el móvil y
prepara los túneles solo. Abre FrameCast y pulsa **Start**.

**Wi-Fi:** conecta el móvil por USB **una vez** (es el emparejamiento: la app
recibe la clave del PC por el cable, que es un canal físico de confianza).
Después, con móvil y PC en la misma red, abre la app y pulsa **Start**: el PC
se descubre automáticamente. Sin ese emparejamiento USB previo, el PC
rechazará el vídeo por seguridad.

## Uso básico

1. Abre OBS (los servicios del PC arrancan solos al detectarlo).
2. No tienes que crear ninguna fuente: FrameCast añade la suya sola (una
   *Video Capture Device* llamada **`FrameCast`**, apuntando a la cámara
   virtual de OBS) al instalar, o la primera vez que emitas con OBS abierto.
   Solo toca esa fuente suya: tus webcams y capturadoras no se tocan nunca.
   (Si prefieres usar una fuente tuya, renómbrala a `FrameCast` — el nombre
   antiguo `PhoneCam` también se acepta.)
3. En el móvil: elige lente (0.6x/1x/3x/10x según dispositivo), resolución,
   composición (16:9, 4:3, 1:1, 9:16, 3:4) y pulsa **Start**.
4. En Zoom/Meet/Discord elige la cámara "OBS Virtual Camera" y, si usas el
   micrófono del móvil con VB-CABLE, el micrófono "CABLE Output".

## Configuración

- **Resolución y FPS**: hasta 4K60 (Pro); la app limita las opciones a lo que
  tu móvil realmente soporta.
- **Composición**: la forma del encuadre (16:9, 4:3, 1:1, 9:16, 3:4). El
  lienzo de OBS puede seguirla automáticamente ("Sync OBS settings").
- **Audio**: AAC 44.1/48/96 kHz, filtros de viento y reducción de ruido.
- **Bitrate**: 10–100 Mbps o personalizado.
- El test de velocidad integrado (Ajustes → PC) mide tu enlace real.

## OBS

- El lienzo de OBS se redimensiona solo a la forma exacta del stream si el
  WebSocket de OBS está activo (Herramientas → Ajustes del servidor
  WebSocket; el instalador intenta activarlo por ti).
- Un cambio hecho en mitad de una sesión se guarda y se aplica cuando la
  salida de OBS se detiene: OBS no permite cambiar el lienzo con una salida
  activa.

## Resolución de problemas

- **"Cannot reach PC"** — el servicio arranca con el PC; si lo detuviste,
  ejecútalo a mano con `pc_receiver\FrameCast_PC.bat`.
- **¿Cómo paro la emisión de pantalla si estoy en otra app?** — con el botón
  **Detener** de la notificación de FrameCast. Si rechazaste el permiso de
  notificaciones, solo podrás pararla volviendo a la app.
- **Wi-Fi no conecta** — ¿hiciste el emparejamiento USB inicial? ¿Están
  móvil y PC en la misma red? ¿El firewall permite Python en redes privadas?
- **No hay imagen en OBS/Zoom** — instala OBS y arranca una vez su cámara
  virtual (botón "Start Virtual Camera").
- **El lienzo de OBS no cambia** — activa el WebSocket de OBS (Herramientas →
  Ajustes del servidor WebSocket); es lo único que FrameCast no puede
  encender sin reiniciar OBS.
- **Logs**: `pc_receiver\logs\` (`control_server.log`, `receiver.log`,
  `discovery.log`) y `pc_receiver\setup_log.txt` para la instalación.

## Limitaciones conocidas (v0.0.14)

- El códec es siempre H.264 (HEVC automático por encima de 4K). No hay
  selector de códec ni HDR en esta versión.
- El modo Wi-Fi requiere un emparejamiento USB inicial.
- El visor muestra la cámara frontal en espejo; el stream no (como la
  mayoría de apps de cámara).
- Los anuncios de esta build son los anuncios **de prueba** de Google.
- Sesiones muy largas en composición vertical con el móvil apaisado usan el
  sensor a resolución completa; en móviles ya calientes puede aparecer
  throttling térmico.

## Privacidad y seguridad

- El vídeo y el audio van **directamente del móvil a tu PC** (USB o red
  local). No pasan por ningún servidor, no hay nube ni cuentas.
- En Wi-Fi el tráfico va sin cifrar dentro de tu red local; la conexión
  requiere una clave que solo se entrega físicamente por USB.
- La app no incluye analítica. La publicidad usa el SDK de AdMob con
  consentimiento previo (GDPR/UMP) donde aplica.

## Licencia y créditos

© 2026 FrameCast. Todos los derechos reservados. Desarrollado de forma
independiente por una sola persona — gracias por probar la alpha y por cada
informe de fallo.

---

# ENGLISH

## What FrameCast is

FrameCast turns your Android phone into a PC webcam, over **USB** or
**Wi-Fi**. Video streams as H.264 (up to 4K60) with synced AAC audio and
shows up on your PC as a regular camera (through OBS Virtual Camera) you can
use in OBS, Zoom, Meet, Discord, and more.

Beyond the camera, FrameCast can cast the **phone's screen**: the top-right
menu switches between Camera and Screen, and in Screen mode the stream keeps
going even if you leave the app (with its permanent notification, as Android
requires). In this mode the limits that apply are the screen's — its
resolution and refresh rate — not the camera's; the app tells you so when
you enable it.

- **Free**: 1080p60 with a small watermark.
- **Temporary Pro by watching an ad**: 4K60, no watermark and no ads for
  12 hours. No subscription, no paywall: during the alpha/beta phase every
  Pro feature unlocks this way.

## Requirements

**PC (Windows 10/11):**
- [Python 3.10+](https://www.python.org/downloads/) — tick
  *"Add python.exe to PATH"* during setup.
- [OBS Studio 28+](https://obsproject.com/) — provides the virtual camera
  and, optionally, automatic canvas sizing.
- For USB: [Android platform-tools (adb)](https://developer.android.com/tools/releases/platform-tools)
  on PATH or in the standard SDK location.
- Optional, to use the phone's microphone in calls:
  [VB-CABLE](https://vb-audio.com/Cable/) (free).

**Phone:**
- Android 8.0 or newer, with a camera.
- Deeply validated on the Samsung Galaxy S23 Ultra; on other devices the app
  adapts resolutions and lenses to what the hardware reports.

## Installation

**On the PC:**
1. Extract the full ZIP into a folder (e.g. `C:\FrameCast`).
2. Double-click **`FrameCast Setup`**. It is silent: it sets up a
   private Python environment, enables OBS's WebSocket and leaves FrameCast
   **starting with the PC**, always ready to receive the phone instantly. A
   summary pops up when done.
3. If Windows asks about the firewall the first time, allow access on
   private networks (needed for Wi-Fi mode).

**On the phone:**
1. Install **FrameCast** from **Google Play**.
2. In the app: Settings → PC control → **"Download the PC installer"** takes
   you to this ZIP, in case it is not on your computer yet.
3. For USB: enable **USB debugging** (Settings → About phone → tap "Build
   number" 7 times → Developer options → USB debugging).

## Connecting and pairing

**USB (recommended):** plug the phone in and accept the USB-debugging
prompt. Nothing else to run: the PC detects the phone and sets up the
tunnels itself. Open FrameCast and tap **Start**.

**Wi-Fi:** connect the phone over USB **once** (that is the pairing: the app
receives the PC's key over the cable, a physically trusted channel). From
then on, with phone and PC on the same network, open the app and tap
**Start**: the PC is discovered automatically. Without that one-time USB
pairing, the PC rejects the video for security.

## Basic use

1. Open OBS (the PC services start on their own when it appears).
2. You do not have to create any source: FrameCast adds its own (a *Video
   Capture Device* named **`FrameCast`**, pointed at OBS's virtual camera)
   at install time, or the first time you stream with OBS open. It only ever
   touches that source of its own — your webcams and capture cards are never
   modified. (If you would rather use a source of your own, rename it to
   `FrameCast`; the old `PhoneCam` name is also accepted.)
3. On the phone: pick a lens (0.6x/1x/3x/10x depending on device),
   resolution, composition (16:9, 4:3, 1:1, 9:16, 3:4) and tap **Start**.
4. In Zoom/Meet/Discord pick the "OBS Virtual Camera" camera and, if you use
   the phone's mic through VB-CABLE, the "CABLE Output" microphone.

## Settings

- **Resolution & FPS**: up to 4K60 (Pro); options are limited to what your
  phone actually supports.
- **Composition**: the frame's shape (16:9, 4:3, 1:1, 9:16, 3:4). OBS's
  canvas can follow it automatically ("Sync OBS settings").
- **Audio**: AAC at 44.1/48/96 kHz, wind filter and noise reduction.
- **Bitrate**: 10–100 Mbps or custom.
- The built-in speed test (Settings → PC) measures your real link.

## OBS

- OBS's canvas resizes itself to the stream's exact shape when OBS's
  WebSocket is enabled (Tools → WebSocket Server Settings; the installer
  tries to enable it for you).
- A change made mid-session is saved and applied once OBS's output stops:
  OBS does not allow reshaping the canvas while an output is active.

## Troubleshooting

- **"Cannot reach PC"** — the service starts with the PC; if you stopped
  it, run it manually with `pc_receiver\FrameCast_PC.bat`.
- **How do I stop a screen cast from another app?** — with the **Stop**
  button on FrameCast's notification. If you declined the notification
  permission, you can only stop it by returning to the app.
- **Wi-Fi will not connect** — did you do the initial USB pairing? Are phone
  and PC on the same network? Does the firewall allow Python on private
  networks?
- **No image in OBS/Zoom** — install OBS and start its Virtual Camera once
  ("Start Virtual Camera" button).
- **OBS canvas not resizing** — enable OBS's WebSocket (Tools → WebSocket
  Server Settings); it is the one thing FrameCast cannot switch on without
  an OBS restart.
- **Logs**: `pc_receiver\logs\` (`control_server.log`, `receiver.log`,
  `discovery.log`) and `pc_receiver\setup_log.txt` for the install step.

## Known limitations (v0.0.14)

- The codec is always H.264 (HEVC automatically above 4K). There is no codec
  selector and no HDR in this release.
- Wi-Fi mode requires a one-time USB pairing.
- The viewfinder mirrors the front camera; the stream does not (like most
  camera apps).
- Ads in this build are Google's **test** ads.
- Very long sessions in a vertical composition with the phone held landscape
  use the sensor at full resolution; on already-hot phones thermal
  throttling may appear.

## Privacy & security

- Video and audio go **directly from the phone to your PC** (USB or local
  network). Nothing passes through any server — no cloud, no accounts.
- Over Wi-Fi the traffic is unencrypted inside your local network; the
  connection requires a key that is only ever handed over physically via
  USB.
- The app contains no analytics. Advertising uses the AdMob SDK behind a
  GDPR/UMP consent flow where applicable.

## License & credits

© 2026 FrameCast. All rights reserved. Independently developed by a single
person — thank you for trying the alpha and for every bug report.
