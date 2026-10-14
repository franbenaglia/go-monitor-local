# Protocolo GoMonitor v1

Contrato entre el agente y sus clientes. Es la única fuente de verdad: se edita antes de cambiar el
código que lo implementa.

- Base: `http://<ip>:<puerto>/api/v1` (puerto por defecto `8080`).
- Formato: JSON en UTF-8. Claves en `snake_case`.
- Fechas: RFC 3339 en UTC, por ejemplo `2026-10-13T23:00:00Z`.
- `protocol_version` actual: `1`.

## 1. Conceptos

**Serie.** Una magnitud escalar que el agente mide a lo largo del tiempo: uso de CPU, temperatura
de un sensor, velocidad de bajada. Se identifica por un `id` estable.

**Lectura.** El valor de una serie en un instante.

**Muestra (snapshot).** Lo que el agente produce en cada ciclo de recolección: las lecturas de ese
ciclo y, si el recolector `system` está activo, los bloques con datos del equipo.

**Recolector.** El componente del agente que produce series. En la v1 existen `system` y
`simulated`.

### Descriptor de serie

```json
{
  "id": "cpu.total_percent",
  "collector": "system",
  "name": "CPU total",
  "kind": "cpu",
  "unit": "percent",
  "min": 0,
  "max": 100
}
```

| Campo | Obligatorio | Descripción |
|---|---|---|
| `id` | sí | Único y estable. Solo `a-z`, `0-9`, `_`, `.`, `-`. Máximo 64 caracteres. |
| `collector` | sí | Recolector que la produce. |
| `name` | sí | Nombre para mostrar. |
| `kind` | sí | Qué representa. Sirve para elegir color e ícono. |
| `unit` | sí | Unidad del valor. Sirve para dar formato. |
| `min`, `max` | no | Rango esperado, para escalar barras y gráficos. |

Valores de `kind` en la v1: `cpu`, `memory`, `network_upload`, `network_download`, `temperature`,
`humidity`, `pressure`, `voltage`, `generic`.

Valores de `unit` en la v1: `percent`, `bytes`, `bytes_per_second`, `celsius`, `hectopascal`,
`volt`, `count`.

Ambas listas son abiertas. Un cliente que recibe un `kind` desconocido lo trata como `generic`; con
un `unit` desconocido muestra el número sin convertir seguido del texto de la unidad.

### Series del recolector `system`

| `id` | `kind` | `unit` |
|---|---|---|
| `cpu.total_percent` | `cpu` | `percent` |
| `memory.used_percent` | `memory` | `percent` |
| `memory.used_bytes` | `memory` | `bytes` |
| `network.bytes_sent_per_second` | `network_upload` | `bytes_per_second` |
| `network.bytes_received_per_second` | `network_download` | `bytes_per_second` |

### Series del recolector `simulated`

| `id` | `kind` | `unit` |
|---|---|---|
| `sim.temperature` | `temperature` | `celsius` |
| `sim.humidity` | `humidity` | `percent` |

## 2. Muestra

```json
{
  "agent_id": "office-pc",
  "hostname": "francisco-pc",
  "operating_system": "linux",
  "architecture": "amd64",
  "timestamp": "2026-10-13T23:00:00Z",
  "uptime_seconds": 123456,
  "readings": [
    { "series_id": "cpu.total_percent", "value": 34.2 },
    { "series_id": "memory.used_percent", "value": 50.0 },
    { "series_id": "sim.temperature", "value": 23.4 }
  ],
  "cpu": {
    "total_percent": 34.2,
    "core_percentages": [25.1, 43.3]
  },
  "memory": {
    "total_bytes": 17179869184,
    "used_bytes": 8589934592,
    "available_bytes": 8589934592,
    "used_percent": 50.0
  },
  "disks": [
    {
      "mount_point": "/",
      "filesystem": "ext4",
      "total_bytes": 500000000000,
      "used_bytes": 250000000000,
      "available_bytes": 250000000000,
      "used_percent": 50.0
    }
  ],
  "network": {
    "bytes_sent_total": 123456789,
    "bytes_received_total": 987654321,
    "bytes_sent_per_second": 1200,
    "bytes_received_per_second": 8400
  },
  "processes": [
    { "pid": 1234, "name": "program", "cpu_percent": 12.5, "memory_percent": 4.2 }
  ]
}
```

Reglas:

- Los campos de identificación, `timestamp` y `readings` están siempre. `readings` puede ser `[]`.
- `cpu`, `memory`, `disks`, `network` y `processes` solo aparecen si el recolector `system` está
  activo. Un cliente no debe asumir que existen.
- `readings` incluye todas las series escalares, también las de `system`. Los bloques tipados
  repiten algunos de esos valores y agregan el detalle que no es escalar.
- Una serie puede faltar en una muestra (sensor que falló, o intervalos distintos en el futuro).
  Ausente no significa cero: el cliente conserva el último valor conocido y su hora.
- `processes` es la unión sin duplicados de los N procesos con más CPU y los N con más memoria,
  ordenada por CPU descendente. Tiene entre N y 2N elementos.
- La primera muestra tras iniciar el agente informa `0` en las velocidades de red, porque no hay
  una muestra anterior con la que comparar.

## 3. Autenticación

Todos los endpoints REST salvo `health` requieren:

```text
Authorization: Bearer <token>
```

Sin cabecera o con token incorrecto la respuesta es `401` con código `unauthorized`. El token nunca
va en la URL. El WebSocket se autentica con un mensaje (sección 6).

## 4. Errores

```json
{
  "error": {
    "code": "invalid_period",
    "message": "The requested history period is not valid"
  }
}
```

| HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `invalid_period` | `period` no es uno de los permitidos. |
| 400 | `invalid_parameter` | Otro parámetro inválido. |
| 401 | `unauthorized` | Falta el token o es incorrecto. |
| 403 | `origin_not_allowed` | Cabecera `Origin` fuera de la lista permitida. |
| 404 | `not_found` | Ruta inexistente. |
| 404 | `unknown_series` | Se pidió una serie que no está en el catálogo. |
| 405 | `method_not_allowed` | Método distinto de `GET`. |
| 413 | `payload_too_large` | Cuerpo de solicitud demasiado grande. |
| 503 | `not_ready` | Todavía no hay ninguna muestra. |
| 503 | `too_many_connections` | Se alcanzó el máximo de clientes WebSocket. |
| 500 | `internal_error` | Falla interna. El detalle queda en el log, no en la respuesta. |

`message` está en inglés y es para diagnóstico. El cliente decide qué mostrar según `code`.

## 5. Endpoints REST

Todos son `GET`.

### `/health` (sin token)

```json
{ "status": "ok", "agent_id": "office-pc", "protocol_version": 1 }
```

### `/status`

Devuelve la última muestra, con el formato de la sección 2.

### `/series`

```json
{ "series": [ { "id": "cpu.total_percent", "collector": "system", "name": "CPU total", "kind": "cpu", "unit": "percent", "min": 0, "max": 100 } ] }
```

Incluye las series activas y las que todavía tienen datos en el historial.

### `/history?period=15m&series=cpu.total_percent,sim.temperature`

| Parámetro | Valores | Por defecto |
|---|---|---|
| `period` | `15m`, `1h`, `6h`, `24h`, `7d` | `15m` |
| `series` | Hasta 16 ids separados por coma | Las cuatro de abajo |

Series por defecto: `cpu.total_percent`, `memory.used_percent`,
`network.bytes_sent_per_second`, `network.bytes_received_per_second`.

```json
{
  "period": "1h",
  "from": "2026-10-13T22:00:00Z",
  "to": "2026-10-13T23:00:00Z",
  "bucket_seconds": 10,
  "series": [
    {
      "id": "cpu.total_percent",
      "unit": "percent",
      "points": [
        { "t": "2026-10-13T22:00:00Z", "avg": 31.5, "min": 28.0, "max": 36.1 }
      ]
    }
  ]
}
```

El agente agrupa las lecturas en intervalos para acotar la respuesta. `t` es el inicio del
intervalo. Los intervalos sin lecturas se omiten, así que el cliente debe dibujar un hueco y no
unir los puntos vecinos cuando la separación supera `bucket_seconds`.

| `period` | `bucket_seconds` | Puntos máximos por serie |
|---|---|---|
| `15m` | 5 | 180 |
| `1h` | 10 | 360 |
| `6h` | 60 | 360 |
| `24h` | 300 | 288 |
| `7d` | 1800 | 336 |

Si el intervalo de recolección configurado es mayor que el valor de la tabla, se usa el intervalo
de recolección.

### `/processes?sort=cpu&limit=20`

`sort` es `cpu` (por defecto) o `memory`. `limit` va de 1 a 100; por defecto devuelve todos los de
la última muestra.

```json
{
  "timestamp": "2026-10-13T23:00:00Z",
  "processes": [ { "pid": 1234, "name": "program", "cpu_percent": 12.5, "memory_percent": 4.2 } ]
}
```

### `/system`

```json
{
  "agent_id": "office-pc",
  "agent_version": "0.1.0",
  "hostname": "francisco-pc",
  "operating_system": "linux",
  "platform": "ubuntu 22.04",
  "architecture": "amd64",
  "cpu_model": "AMD Ryzen 5 5600G",
  "cpu_cores": 12,
  "memory_total_bytes": 17179869184,
  "uptime_seconds": 123456
}
```

### `/network`

```json
{
  "timestamp": "2026-10-13T23:00:00Z",
  "total": {
    "bytes_sent_total": 123456789,
    "bytes_received_total": 987654321,
    "bytes_sent_per_second": 1200,
    "bytes_received_per_second": 8400
  },
  "interfaces": [
    {
      "name": "wlp3s0",
      "bytes_sent_total": 123456789,
      "bytes_received_total": 987654321,
      "bytes_sent_per_second": 1200,
      "bytes_received_per_second": 8400
    }
  ]
}
```

`total` no incluye interfaces loopback.

### `/disks`

```json
{ "timestamp": "2026-10-13T23:00:00Z", "disks": [ { "mount_point": "/", "filesystem": "ext4", "total_bytes": 500000000000, "used_bytes": 250000000000, "available_bytes": 250000000000, "used_percent": 50.0 } ] }
```

### `/config/public`

```json
{
  "agent_id": "office-pc",
  "protocol_version": 1,
  "collection_interval_seconds": 5,
  "retention_seconds": 604800,
  "top_processes": 10,
  "collectors": ["system"]
}
```

Nunca incluye el token ni rutas del sistema de archivos.

### `/interfaces`

```json
{
  "port": 8080,
  "interfaces": [
    { "name": "wlp3s0", "address": "192.168.1.50", "recommended": true },
    { "name": "docker0", "address": "172.17.0.1", "recommended": false }
  ]
}
```

`recommended` es `false` para loopback, Docker, VPN e interfaces virtuales.

`/system`, `/network`, `/disks` y `/processes` devuelven `404 not_found` si el recolector `system`
está desactivado.

## 6. WebSocket

```text
ws://<ip>:<puerto>/api/v1/ws
```

Todos los mensajes son texto JSON con un campo `type`.

### Secuencia

1. El cliente abre la conexión.
2. Antes de 5 segundos envía:
   ```json
   { "type": "authenticate", "token": "<token>" }
   ```
3. El servidor responde:
   ```json
   { "type": "authenticated", "agent_id": "office-pc", "protocol_version": 1, "collection_interval_seconds": 5 }
   ```
4. El servidor envía de inmediato la última muestra, si existe, y luego una por cada ciclo:
   ```json
   { "type": "metrics", "data": { } }
   ```
   `data` tiene el formato de la sección 2.

### Latido

El cliente puede enviar `{ "type": "ping" }` y el servidor responde `{ "type": "pong" }`. Además el
servidor envía tramas ping del protocolo WebSocket cada 20 segundos y cierra la conexión si no
recibe respuesta en 10 segundos.

El cliente debe considerar muerta la conexión si pasa tres veces
`collection_interval_seconds` sin recibir ningún mensaje.

### Errores y cierre

Antes de cerrar por un error, el servidor envía cuando puede:

```json
{ "type": "error", "error": { "code": "unauthorized", "message": "Invalid token" } }
```

| Código de cierre | Motivo | Qué hace el cliente |
|---|---|---|
| 1001 | El agente se está apagando. | Reconectar con backoff. |
| 1009 | Mensaje mayor al límite. | Error de programación; no reconectar en bucle. |
| 4001 | No se autenticó a tiempo. | Reconectar. |
| 4003 | Token incorrecto. | No reconectar; mostrar "Token incorrecto". |
| 4008 | Demasiados mensajes. | Reconectar con backoff. |
| 4400 | Mensaje inválido o `type` desconocido. | Error de programación; no reconectar en bucle. |

### Límites

| Límite | Valor |
|---|---|
| Tamaño máximo de mensaje entrante | 4 KiB |
| Mensajes entrantes | 20 cada 10 segundos |
| Clientes simultáneos | 8 (configurable); el siguiente recibe `503 too_many_connections` |
| Plazo para autenticarse | 5 segundos |

Un cliente lento no frena al agente: si no llega a leer, el servidor descarta las muestras viejas y
le entrega solo la más reciente. El cliente no debe asumir que recibe todas las muestras; para
reconstruir lo perdido existe `/history`.

## 7. Cambios de versión

- Agregar campos, series, valores de `kind` o de `unit` no cambia `protocol_version`. Los clientes
  ignoran lo que no conocen.
- Quitar o cambiar el significado de un campo sí lo cambia, y el cliente debe avisar si el agente
  informa una versión mayor a la que entiende.
