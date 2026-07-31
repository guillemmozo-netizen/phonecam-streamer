# RC 0.0.14-rc1 — validación manual

**Artefacto:** `rc/phonecam-0.0.14-rc1.apk` · versionCode 14
**SHA256:** `5B036C3CC47840D51D0E9B0F75F8E5E969D7A0AEB2A6FBFD6F4FB97C089FF87F`
**Rama:** `fix/camerax-viewport` · **Automatizado:** 167 tests Android + 249 PC, todos en verde

Esta RC cierra la investigación de geometría de vídeo: rotación, composición,
aspecto, fugas de encoder y sincronización con OBS. Lo que sigue es **solo lo
que las pruebas automáticas no pueden ver** — cada punto necesita ojos en la
pantalla o manos en el móvil.

---

## Preparación (una vez)

```bash
adb install -r "C:\open source\rc\phonecam-0.0.14-rc1.apk"
```

```bash
adb logcat -c && adb logcat -v time -s MainActivity:V CameraStreamer:V Camera2CaptureSource:V > "C:\open source\rc_validacion.txt"
```

En el PC: control server corriendo (`pc_receiver/control_server.py`) y OBS
abierto con la fuente de PhoneCam visible.

**Comprobación previa** — si esto falla, nada de lo demás es concluyente:

- [ ] `adb reverse --list` muestra 8787 y 8790
- [ ] Al emitir, las métricas del log muestran `no_connection=0` y bitrate > 0

---

## Bloque A · Geometría de imagen (prioridad máxima)

Lo corregido aquí nunca se ha visto funcionando con el build final: la
rotación por textura se dedujo de una observación en vivo y se validó por
simulación, no por inspección visual.

- [ ] **A1 · 1x vertical** — emite con el móvil vertical, cámara trasera 1x.
      En OBS: imagen **derecha** (no tumbada) y **sin deformar**.
- [ ] **A2 · 1x apaisado** — sin parar, gira el móvil a apaisado.
      En OBS: se endereza, **FOV completo**, sin zoom ni estirón.
- [ ] **A3 · 0.6x vertical** — misma orientación que A1, lente 0.6x.
      Debe salir **igual de derecha** que A1 (paridad entre backends).
- [ ] **A4 · Invariante 1:1** — composición 1:1, gira el móvil en las cuatro
      posiciones. **No debe deformarse en ninguna** (es el único aspecto
      autotranspuesto; si se deforma, el modelo de geometría está incompleto).
- [ ] **A5 · 9:16 apaisado** — composición 9:16 con el móvil apaisado.
      Debe verse **nítida** (esta celda captura ahora en UHD). Comprueba en el
      log: `capture need: 1080x1920 (… hold=1)` y `buffer=1216x2160`.
- [ ] **A6 · 4:3 y 3:4** — una sesión de cada, vertical y apaisado.
      Sin estirones en ninguna de las cuatro combinaciones.

**Cómo leer un fallo:** la línea `geom[...]` da `θ`, `texRot`, `vertex`,
`buffer` y `encoder`. Si algo sale girado, apunta esa línea junto con la
orientación física — identifica el término equivocado sin necesidad de repetir
la investigación.

---

## Bloque B · Ciclo de sesión

Correcciones verificadas en código y log, **nunca observadas en pantalla**.

- [ ] **B1 · Visor de Camera2 (F12)** — emite con 1x (backend camera2) y mira
      la **pantalla del móvil**: el visor debe conservar su proporción con
      bandas, **no estirarse a pantalla completa**. Es lo único de la RC sin
      ninguna confirmación previa.
- [ ] **B2 · Voltear cámara en caliente (F10)** — con el stream vivo, pulsa
      voltear. Esperado: corte breve anunciado, sesión nueva coherente, sin
      `capture failed` en el log, imagen frontal estable al girar el móvil.
- [ ] **B3 · Cambio de lente en caliente** — igual con los chips 0.6x/3x/10x.
      Mismo comportamiento que B2.
- [ ] **B4 · Cambio de geometría en Ajustes (F7)** — emitiendo a 16:9, entra
      en Ajustes, cambia a 4:3 y vuelve **sin parar**. Esperado: log
      `session geometry changed in Settings — restarting the stream`, corte
      breve, y OBS a 1440×1080 sin deformar.
- [ ] **B5 · Ajuste cosmético no corta** — emitiendo, cambia solo la
      cuadrícula en Ajustes y vuelve. **No debe reiniciar** la sesión.
- [ ] **B6 · Fuga de encoder (F6)** — emitiendo, entra y sal de Ajustes 4
      veces seguidas. Esperado: `releasing previous encoder before rebind
      replacement` cada vez, **ningún** `failed to create H.264 encoder`, y el
      stream vivo al final.

---

## Bloque C · OBS

- [ ] **C1 · Lienzo sigue a la composición** — con el stream parado, cambia la
      composición en Ajustes. El lienzo de OBS debe seguirla
      (`OBS canvas updated: AxB -> CxD` en el log del receiver).
- [ ] **C2 · Cambio diferido** — cambia la composición **mientras** emites.
      Esperado: se guarda y se aplica al parar la salida, no se pierde.
- [ ] **C3 · Fuente ajena intacta** — si tienes otra webcam en la escena,
      confirma que **su transform no se toca**. La fuente de PhoneCam solo se
      ajusta si se llama exactamente `PhoneCam` (o lo que diga
      `PHONECAM_OBS_SOURCE`); si no, el log lo avisa y no modifica nada.

---

## Bloque D · Resistencia (opcional, pero es el hueco real)

- [ ] **D1 · Térmica larga** — sesión de **10-15 minutos en 9:16 apaisado**
      (la celda que captura en UHD). Vigila `Thermal Status` y los fps: los
      75 s medidos dieron estado 0 y 29.97 fps, pero no dicen nada de media
      hora. Si aparece throttling, la escalada de M2 debería revisarse.
- [ ] **D2 · Watchdog anticongelación** — difícil de provocar a voluntad; si
      alguna vez la cámara se congela, debe aparecer
      `capture frozen for ...ms — restarting the session (watchdog)` y
      recuperarse sola en ~8 s en lugar de quedarse muerta.

---

## Criterio de promoción

**Se promociona a alpha pública** si A1-A6 y B1-B6 pasan sin fallos.

**Se bloquea** si falla cualquiera de: A1, A2, A4 (rotación o deformación
básicas), B6 (fuga de encoder) o C3 (tocar una fuente ajena en OBS).

**No bloquea** un fallo en D1/D2: se documenta como limitación conocida y se
trata en la siguiente iteración.

---

## Conocido y no incluido en esta RC

- **F3** — 9:16, 1:1 y 3:4 usan siempre CameraX; Camera2 (y su 4K60) solo
  atiende 16:9 y 4:3 en la lente wide. Sin impacto visual tras M1/M2.
- **F4** — carrera de apertura de Camera2: no reproducida en ~15 aperturas; el
  latch que deshabilita el backend tras un fallo sigue siendo por proceso.
- **F8** — el visor espeja la cámara frontal y el stream no. Decisión de
  producto pendiente, no defecto.
- **Arranque automático del control server** — el watcher de túneles USB solo
  corre si el servidor está arrancado; `pc_receiver/install_startup.bat` lo
  hace permanente, y es un cambio en el arranque del sistema que no se ha
  aplicado.
