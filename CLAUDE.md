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
agent/     Agente Go (módulo independiente)
android/   App Android nativa en Kotlin + Jetpack Compose
docs/      Documentos de arriba
Makefile   `make check` verifica todo lo que exista
```

## Comandos

```bash
make check            # vet + tests del agente, tests + build de la app
make check-agent
make check-android
```

Los comandos propios de cada lado se agregan acá cuando existan (ver hitos en `docs/PROGRESS.md`).

## Reglas de trabajo

- El modelo de datos es genérico: toda magnitud escalar es una *serie* con id, tipo y unidad. No
  agregar campos fijos para una métrica nueva; agregar una serie (ver D1 en `docs/DECISIONS.md`).
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
