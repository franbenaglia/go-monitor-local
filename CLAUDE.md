# GoMonitor Local

Agente en Go que recopila métricas y lecturas de sensores, las guarda en SQLite y las sirve por
REST y WebSocket en la red local. Una app Android nativa se conecta directamente por IP. Sin
servidor central, sin cloud, sin Internet.

## Al empezar una sesión

1. Leer `docs/PROGRESS.md`: dice qué está hecho, qué está verificado y cuál es el próximo paso.
2. Correr `make check` para confirmar el estado real antes de confiar en lo documentado.
3. Consultar `docs/PROTOCOL.md` antes de tocar cualquier cosa que cruce entre agente y app.

## Documentos

| Archivo | Contenido | Cuándo se edita |
|---|---|---|
| `docs/SPEC.md` | Requisito original, sin modificar | Nunca |
| `docs/DECISIONS.md` | Decisiones y su porqué. Si contradice a `SPEC.md`, gana `DECISIONS.md` | Al tomar una decisión no obvia |
| `docs/PROTOCOL.md` | Contrato REST y WebSocket, única fuente de verdad | Antes de cambiar el código que lo implementa |
| `docs/PROGRESS.md` | Hitos, criterios de aceptación y próximo paso | Al cerrar cada hito y al terminar cada sesión |

## Estructura

```text
agent/     Agente Go (módulo `gomonitor/agent`); paquetes descritos en `agent/README.md`
android/   App Android nativa en Kotlin + Jetpack Compose; capas descritas en `android/README.md`
docs/      Documentos de arriba
Makefile   `make check` verifica todo lo que exista
```

## Comandos

```bash
make check            # todo lo de abajo
make check-agent      # go vet + go test -race
make check-android    # pruebas unitarias de la app + assembleDebug
make check-e2e        # red de la app (OkHttp + ConnectionManager reales) contra el agente
```

Agente (desde `agent/`):

```bash
make build            # bin/gomonitor-agent y bin/gomonitor-probe
make test             # go vet + go test -race
make cross            # Linux, Windows y Raspberry Pi
./bin/gomonitor-agent -port 18080 -collectors system,simulated
./bin/gomonitor-probe -address 127.0.0.1:18080 -count 3    # cliente WebSocket de prueba
```

App (desde `android/`, siempre con el wrapper):

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
./gradlew installDebug         # solo con el teléfono conectado por adb
```

## Reglas de trabajo

- El modelo de datos es genérico: toda magnitud escalar es una *serie* con id, tipo y unidad. No
  agregar campos fijos para una métrica nueva; agregar una serie (ver D1 en `docs/DECISIONS.md`).
- En la app, la lógica va en `core/`, sin dependencias de Android, para probarla en la JVM. Las
  pantallas solo muestran estado y llaman al ViewModel.
- Un recolector nuevo implementa la interfaz `Collector` y no toca API, almacenamiento ni app.
- El código de hardware (GPIO, I2C) va detrás de build tags para que todo compile en amd64.
- Cambios de protocolo: primero `docs/PROTOCOL.md`, después agente y app en el mismo hito.
- Un hito se marca como hecho solo si `make check` pasa. Lo que no se pudo verificar se anota como
  tal en `docs/PROGRESS.md`.
- No iniciar el emulador de Android (D8). Para probar en dispositivo, pedir el teléfono por adb.
- Un commit por hito o por paso coherente, con mensaje en español.
- Idioma: documentación y textos de la app en español; identificadores, claves JSON, códigos de
  error y logs en inglés.
- Nunca registrar el token en logs ni devolverlo por la API.
