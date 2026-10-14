# Decisiones

Cada entrada dice qué se decidió y por qué. Cuando una decisión contradice a `SPEC.md`, vale la
decisión. Las entradas no se borran: si una se revierte, se agrega otra que la reemplaza.

## D1 — Modelo de datos genérico basado en series (2026-10-13)

**Decisión.** Toda magnitud escalar que el agente mide es una *serie* con `id`, `kind` y `unit`,
publicada en un catálogo (`GET /api/v1/series`). El historial y los gráficos trabajan sobre series,
no sobre campos fijos. Las métricas de sistema escalares (CPU total, memoria, velocidad de red)
también son series. Los datos estructurados de la computadora (núcleos, discos, procesos) se
mantienen como bloques tipados opcionales en la muestra.

**Por qué.** El mismo proyecto se va a adaptar a una Raspberry Pi que transmita telemetría de
sensores en lugar de (o además de) métricas del equipo. Con campos fijos, eso obligaría a migrar el
esquema, cambiar el protocolo y rehacer pantallas. Con series, agregar un sensor es agregar un
recolector.

**Consecuencias.**
- El agente tiene una interfaz `Collector`; `system` es una implementación más.
- Existe un recolector `simulated` para desarrollar y probar sin hardware.
- Los bloques `cpu`, `memory`, `disks`, `network` y `processes` son opcionales: un agente que solo
  lee sensores no los envía, y la app no debe asumir que están.

## D2 — App Android nativa en Kotlin, sin Capacitor (2026-10-13)

**Decisión.** La app se hace en Kotlin con Jetpack Compose. Reemplaza a React + Ionic + Capacitor
de las secciones 5, 7 y 11 de `SPEC.md`.

**Por qué.** Lo difícil de esta app es la capa de red, y el WebView la complica:
- Capacitor sirve la app desde `https://localhost`, y desde ese origen el WebView bloquea `http://`
  y `ws://` por contenido mixto. Nativo no tiene ese problema.
- `fetch` solo informa un error genérico. Nativo entrega excepciones distintas para conexión
  rechazada, timeout y host inalcanzable, que es lo que la sección 6 pide distinguir.
- El descubrimiento mDNS (sección 8) usa `NsdManager`, que es API del sistema. Con Capacitor
  requería escribir un plugin.
- No hace falta CORS para la app.

**Qué se pierde.**
- El modo web `npm run dev` de la sección 11. Se reemplaza por pruebas del agente con `curl` y un
  cliente WebSocket de prueba, pruebas unitarias de la app en la JVM, y pruebas en el teléfono
  físico por adb (ver D8).
- Portabilidad a iOS.

**Pila.** Kotlin, Jetpack Compose con Material 3, OkHttp para HTTP y WebSocket,
kotlinx.serialization, DataStore para las conexiones guardadas, gráficos propios sobre Canvas (sin
dependencia de gráficos). JDK 17. El directorio se llama `android/` en lugar de `mobile/`.

## D3 — Lecturas en tabla angosta (2026-10-13)

**Decisión.** Las lecturas se guardan en una tabla `readings(series_id, ts, value)`. Discos y
procesos tienen tablas propias con una columna por campo.

**Por qué.** `SPEC.md` pide columnas consultables y no un JSON opaco. Una tabla ancha con una
columna por métrica cumple eso pero exige migrar el esquema por cada sensor nuevo, lo que contradice
D1. En la tabla angosta el valor sigue siendo una columna consultable e indexada por serie y fecha.

**Consecuencias.** El porcentaje por núcleo y los contadores acumulados de red se envían en vivo
pero no se guardan en el historial: multiplicarían las filas sin aportar a los gráficos pedidos.

## D4 — `/api/v1/health` no requiere token (2026-10-13)

**Decisión.** `health` responde sin autenticación con datos mínimos. El resto de la API exige
`Authorization: Bearer`.

**Por qué.** La app prueba primero `health` y después se autentica. Si `health` exigiera token, no
se podría distinguir "equipo no encontrado" de "token incorrecto".

## D5 — CORS cerrado por defecto (2026-10-13)

**Decisión.** El agente no envía cabeceras CORS salvo para los orígenes listados en
`cors_allowed_origins`, que por defecto está vacío. El WebSocket rechaza conexiones que traigan una
cabecera `Origin` fuera de esa lista; los clientes nativos no la envían.

**Por qué.** Tras D2 ningún cliente previsto es un navegador. Dejarlo cerrado evita que una página
abierta en otro equipo de la red use el agente. La opción queda para un cliente web futuro.

## D6 — Descubrimiento mDNS en segunda etapa (2026-10-13)

**Decisión.** La primera versión solo tiene ingreso manual de IP. mDNS se agrega después de que la
conexión manual esté verificada.

**Por qué.** Lo pide así la sección 8 de `SPEC.md`, y varios routers y hotspots filtran multicast,
así que nunca puede ser el único camino.

## D7 — Bibliotecas previstas para el agente (2026-10-13)

**Decisión.** `gopsutil` v4, `modernc.org/sqlite` (Go puro, sin CGO), `github.com/coder/websocket`,
`gopkg.in/yaml.v3`, `net/http` y `log/slog` de la biblioteca estándar.

**Por qué.** Sin CGO, la compilación cruzada a Windows y a ARM (Raspberry Pi) es un solo comando.
Las versiones exactas se fijan al crear el módulo; si alguna resulta inadecuada, se registra acá.

## D8 — No se usa el emulador de Android (2026-10-13)

**Decisión.** La app se verifica con pruebas unitarias en la JVM y compilando el APK. Las pruebas
en dispositivo se hacen solo en el teléfono físico, cuando Francisco lo conecta por adb.

**Por qué.** El emulador deja la máquina de desarrollo sin responder. Lo pidió Francisco.

**Consecuencias.** La lógica de la app (validación, máquina de estados de conexión, reconexión,
formato, transformación del historial) va en clases sin dependencias de Android para poder probarla
en la JVM. Lo que solo se ve en pantalla queda como "sin verificar en dispositivo" en
`PROGRESS.md` hasta que haya teléfono conectado.

## Pendientes de decidir

- Intervalo de recolección por recolector (hoy hay uno global). El protocolo ya lo permite porque
  las muestras pueden ser parciales.
- Escrituras en lote a SQLite para reducir el desgaste de la tarjeta SD en Raspberry Pi.
- Bibliotecas de sensores para la Pi (candidata: `periph.io`).
