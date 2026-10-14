# GoMonitor Agent

Agente en Go que recopila métricas de la computadora y lecturas de sensores, guarda el historial en
SQLite y lo sirve en la red local por REST y WebSocket. El contrato está en
[../docs/PROTOCOL.md](../docs/PROTOCOL.md).

## Uso

```bash
go run ./cmd/gomonitor-agent          # directamente
make build && ./bin/gomonitor-agent   # compilado
```

Al iniciar muestra la dirección que hay que cargar en el celular:

```text
GoMonitor Agent iniciado

Conecta el celular a la misma red Wi-Fi.

Direcciones disponibles:
http://192.168.1.50:8080
```

Se detiene con `Ctrl+C` o `SIGTERM`; avisa a los clientes conectados antes de cerrar.

## Configuración

Prioridad, de mayor a menor:

1. flags de línea de comandos;
2. variables de entorno;
3. archivo YAML;
4. valores predeterminados.

El archivo YAML se busca en `./config.yaml`. Otra ruta se indica con `-config` o con
`GOMONITOR_CONFIG`; en ese caso el archivo tiene que existir. Hay un ejemplo comentado en
[configs/config.example.yaml](configs/config.example.yaml).

| Clave YAML | Flag | Variable de entorno | Predeterminado |
|---|---|---|---|
| `agent_id` | `-agent-id` | `GOMONITOR_AGENT_ID` | `office-pc` |
| `listen_address` | `-listen-address` | `GOMONITOR_LISTEN_ADDRESS` | `0.0.0.0` |
| `port` | `-port` | `GOMONITOR_PORT` | `8080` |
| `collection_interval` | `-collection-interval` | `GOMONITOR_COLLECTION_INTERVAL` | `5s` |
| `api_token` | `-api-token` | `GOMONITOR_API_TOKEN` | `change-this-token` |
| `top_processes` | `-top-processes` | `GOMONITOR_TOP_PROCESSES` | `10` |
| `database_path` | `-database-path` | `GOMONITOR_DATABASE_PATH` | `./data/gomonitor.db` |
| `retention` | `-retention` | `GOMONITOR_RETENTION` | `7d` |
| `log_level` | `-log-level` | `GOMONITOR_LOG_LEVEL` | `info` |
| `collectors` | `-collectors` | `GOMONITOR_COLLECTORS` | `system` |
| `cors_allowed_origins` | `-cors-allowed-origins` | `GOMONITOR_CORS_ALLOWED_ORIGINS` | vacío |
| `max_ws_clients` | `-max-ws-clients` | `GOMONITOR_MAX_WS_CLIENTS` | `8` |

Las listas se escriben separadas por coma en flags y variables de entorno:
`-collectors system,simulated`.

El token nunca se escribe en los logs ni se devuelve por la API. Mientras siga el valor
predeterminado, el agente lo advierte al iniciar.

## Recolectores

| Nombre | Qué produce |
|---|---|
| `system` | CPU, memoria, discos, red y procesos de esta computadora. |
| `simulated` | Temperatura y humedad ficticias, para probar el camino de sensores sin hardware. |

Para agregar un sensor se implementa la interfaz `Collector` de
[internal/collector/collector.go](internal/collector/collector.go) y se registra en
[cmd/gomonitor-agent/main.go](cmd/gomonitor-agent/main.go) y en `KnownCollectors` de
[internal/config/config.go](internal/config/config.go). La API, el almacenamiento y la app no
cambian.

## Probar sin la app

```bash
curl http://127.0.0.1:8080/api/v1/health
curl -H "Authorization: Bearer change-this-token" http://127.0.0.1:8080/api/v1/status
curl -H "Authorization: Bearer change-this-token" "http://127.0.0.1:8080/api/v1/history?period=1h"

# Cliente WebSocket de prueba: se conecta y autentica igual que el celular.
./bin/gomonitor-probe -address 127.0.0.1:8080 -token change-this-token -count 3
./bin/gomonitor-probe -raw -count 1     # muestra el JSON completo
```

## Pruebas y compilación

```bash
make test    # go vet + go test -race
make build   # bin/gomonitor-agent y bin/gomonitor-probe
make cross   # Linux amd64, Windows amd64, Raspberry Pi arm64 y armv6
```

El agente no usa CGO, así que la compilación cruzada no necesita herramientas adicionales.

## Estructura

```text
cmd/gomonitor-agent/   Programa principal
cmd/gomonitor-probe/   Cliente WebSocket de prueba
internal/config/       Carga y validación de la configuración
internal/collector/    Interfaz Collector, recolectores y ciclo de recolección
internal/storage/      Historial en SQLite y retención
internal/api/          Endpoints REST, autenticación y límites
internal/wshub/        Clientes WebSocket
internal/netinfo/      Detección de direcciones IP locales
internal/models/       Tipos compartidos con la forma JSON del protocolo
internal/apierror/     Formato de error común
```
