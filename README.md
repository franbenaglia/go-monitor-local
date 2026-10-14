# GoMonitor Local

Monitoreo de una computadora desde el celular, por la red local. Un agente en Go recopila métricas
y lecturas de sensores, las guarda y las sirve; una app Android se conecta directo a su dirección
IP. No hay servidor central, servicios en la nube ni necesidad de Internet.

## 1. Arquitectura

```text
Computadora                                  Celular Android
┌──────────────────────────────┐             ┌──────────────────────┐
│ GoMonitor Agent              │             │ GoMonitor Mobile     │
│                              │  HTTP REST  │                      │
│ recolectores → SQLite        │ ◄────────── │ historial, pruebas   │
│      │                       │  WebSocket  │                      │
│      └────► servidor local   │ ──────────► │ dashboard en vivo    │
│             0.0.0.0:8080     │             │                      │
└──────────────────────────────┘             └──────────────────────┘
              └───────────── misma red Wi-Fi ─────────────┘
```

- **Agente** ([agent/](agent/)): un solo ejecutable. Cada 5 segundos toma una muestra, la guarda
  en SQLite y la envía por WebSocket a los celulares conectados. El historial se guarda aunque no
  haya ningún celular.
- **App** ([android/](android/)): Kotlin y Jetpack Compose. Muestra el dashboard en vivo, el
  historial, los procesos y la lista de computadoras guardadas.
- **Protocolo** ([docs/PROTOCOL.md](docs/PROTOCOL.md)): el contrato entre ambos.

El modelo de datos es genérico: cada magnitud es una *serie* con nombre, tipo y unidad. Las
métricas de la computadora son series, y un sensor conectado a una Raspberry Pi también. Agregar
un sensor es agregar un recolector al agente; la app lo muestra sin cambios.

## 2. Requisitos

| Para | Hace falta |
|---|---|
| Ejecutar el agente | Linux o Windows. El ejecutable no tiene dependencias. |
| Compilar el agente | Go 1.27 o superior. |
| Compilar la app | JDK 17 y Android SDK. |
| Usar la app | Android 8.0 (API 26) o superior. |

## 3. Instalación

```bash
git clone <este repositorio>
cd go-monitor-local
make -C agent build
```

Queda `agent/bin/gomonitor-agent`. Se puede copiar a cualquier carpeta; crea su base de datos en
`./data/` relativa al directorio desde donde se lo inicia.

## 4. Inicio del agente

```bash
cd agent
./bin/gomonitor-agent            # compilado
go run ./cmd/gomonitor-agent     # o directamente desde el código
```

Al arrancar muestra la dirección a cargar en el celular:

```text
GoMonitor Agent iniciado

Conecta el celular a la misma red Wi-Fi.

Direcciones disponibles:
http://192.168.1.50:8080
```

Se detiene con `Ctrl+C`. La configuración (puerto, token, intervalo, retención) está descrita en
[agent/README.md](agent/README.md).

## 5. Compilación de la app

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # instala en el teléfono conectado por USB con depuración activada
```

Sin cable: copiar el APK al teléfono y abrirlo, permitiendo instalar desde orígenes desconocidos.

## 6. Conexión del celular

1. Conectar el celular a la misma red Wi-Fi que la computadora.
2. Abrir GoMonitor Mobile, ir a **Conexiones** y tocar **Agregar computadora**.
3. Cargar un nombre, la **dirección IP** y el **puerto** que mostró el agente, y el **token**.
4. Tocar **Probar conexión**: confirma que el agente responde y que el token es correcto.
5. Tocar **Conectar**. El dashboard empieza a recibir datos.

Con **Conexión automática** activada, la app se conecta sola a esa computadora al abrirse. Si la
conexión se corta, reintenta con una espera que se duplica en cada intento hasta un minuto.

## 7. Cómo encontrar la IP local

La forma más simple es leerla en el mensaje de inicio del agente, que ya descarta las interfaces
que no sirven (loopback, Docker, VPN). A mano:

```bash
ip -4 addr show          # Linux: buscar la interfaz Wi-Fi (wlan0, wlp...) o Ethernet (eth0, enp...)
hostname -I              # Linux: todas las direcciones en una línea
ipconfig                 # Windows: "Dirección IPv4" del adaptador Wi-Fi o Ethernet
```

Sirve una dirección privada: `192.168.x.x`, `10.x.x.x` o `172.16.x.x` a `172.31.x.x`. La IP puede
cambiar cuando el router la renueva; para evitarlo se puede reservar en la configuración del router.

## 8. Configuración del firewall

Si el celular no llega al agente, lo más probable es que el firewall de la computadora bloquee el
puerto. Hay que abrir solo ese puerto, sin desactivar el firewall.

**Linux con ufw** (Ubuntu, Debian):

```bash
sudo ufw allow 8080/tcp
# Más estricto: solo desde la red local
sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp
```

**Linux con firewalld** (Fedora, RHEL):

```bash
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

**Windows**, en PowerShell como administrador:

```powershell
New-NetFirewallRule -DisplayName "GoMonitor Agent" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
```

En Windows la red tiene que estar marcada como **Privada**; en una red marcada como Pública la
regla anterior no aplica.

## 9. Uso con router Wi-Fi

Es el caso normal: computadora y celular conectados al mismo router. Dos cosas pueden impedirlo:

- **Aislamiento de clientes** ("AP isolation", "aislamiento de AP"): algunos routers, sobre todo en
  redes de invitados, impiden que los equipos se vean entre sí. Hay que desactivarlo o usar la red
  principal.
- **Bandas separadas**: que uno esté en 2,4 GHz y el otro en 5 GHz no es problema si son la misma
  red; sí lo es si son redes distintas con rangos de IP distintos.

No hace falta que el router tenga salida a Internet.

## 10. Uso con hotspot del celular

1. Activar el punto de acceso Wi-Fi en el celular.
2. Conectar la computadora a esa red.
3. Iniciar el agente: muestra la IP que el celular le asignó a la computadora.
4. Cargar esa IP en la app.

Funciona sin datos móviles. Si la conexión falla en este modo, la app puede indicar que el celular
"no parece estar en la misma red": con el hotspot activo el teléfono no figura como conectado a un
Wi-Fi. Revisar que la computadora siga conectada al hotspot y que la IP sea la actual.

## 11. Configuración del token

El token es la contraseña que la app presenta al agente. El valor de fábrica es
`change-this-token`; el agente advierte al iniciar mientras no se cambie.

```bash
./bin/gomonitor-agent -api-token "un-token-largo-y-dificil"
GOMONITOR_API_TOKEN="un-token-largo-y-dificil" ./bin/gomonitor-agent
```

O en `config.yaml`, junto al ejecutable:

```yaml
api_token: "un-token-largo-y-dificil"
```

Para generar uno: `openssl rand -hex 24`. El mismo valor va en el campo **Token** de la app. El
agente nunca lo escribe en sus logs ni lo devuelve por la API.

## 12. Solución de problemas

| La app dice | Qué revisar |
|---|---|
| **Equipo no encontrado** | La IP. Que el agente esté iniciado. Que ambos estén en la misma red. |
| "el agente no está escuchando en ese puerto" | La computadora responde: revisar el puerto, o que el agente esté iniciado. |
| "no respondió a tiempo" | El firewall (sección 8) o el aislamiento de clientes del router (sección 9). |
| "no parece estar en la misma red" | El celular está con datos móviles o en otro Wi-Fi. |
| **Token incorrecto** | El token de la app tiene que ser igual al `api_token` del agente. |
| "responde otro programa" | En esa IP y puerto hay otro servicio; revisar el puerto. |
| "máximo de clientes conectados" | Hay 8 clientes conectados; subir `max_ws_clients` o cerrar alguno. |
| **Reconectando** sin parar | El agente se detuvo o la computadora se suspendió. La app sigue intentando sola. |

Para separar un problema de red de uno de la app, probar desde el navegador del celular:
`http://IP:8080/api/v1/health`. Si responde `{"status":"ok",...}`, la red está bien.

Para ver qué hace el agente: iniciarlo con `-log-level debug`.

Si el agente no arranca con "address already in use", otro programa usa el puerto: elegir otro con
`-port`.

## 13. Pruebas

```bash
make check           # todo: agente, app y prueba de punta a punta
make check-agent     # go vet + go test -race
make check-android   # pruebas unitarias de la app + compilación del APK
make check-e2e       # la capa de red de la app contra un agente real, sin teléfono
```

Por separado:

```bash
cd agent && go test ./... && go vet ./... && go test -race ./...
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug
```

## 14. Compilación para Linux y Windows

```bash
cd agent
make cross
```

Genera en `agent/bin/`:

| Archivo | Plataforma |
|---|---|
| `gomonitor-agent-linux-amd64` | Linux de escritorio |
| `gomonitor-agent-windows-amd64.exe` | Windows |
| `gomonitor-agent-linux-arm64` | Raspberry Pi de 64 bits |
| `gomonitor-agent-linux-armv6` | Raspberry Pi de 32 bits y Pi Zero |

El agente no usa CGO, así que se compila para cualquiera de ellas desde una sola máquina, sin
herramientas adicionales.

## 15. Limitaciones de HTTP local

La comunicación va **sin cifrar**. Cualquiera en la misma red que capture tráfico puede leer las
métricas y el token. Eso es aceptable en una red doméstica o un hotspot propio, y no lo es en una
red compartida o pública (oficina abierta, hotel, café).

Mientras tanto:

- cambiar el token de fábrica;
- no redirigir el puerto del agente hacia Internet desde el router;
- el agente no ejecuta comandos ni modifica nada en la computadora: quien obtenga el token puede
  leer métricas, no controlar el equipo.

Android no permite autorizar tráfico sin cifrar solo hacia direcciones privadas, por lo que la app
lo permite en general. Detalle en [android/README.md](android/README.md).

## 16. Evolución futura

- **HTTPS y WSS**: certificado autofirmado generado por el agente y fijado en la app al guardar la
  conexión, para usar el sistema en redes no confiables.
- **Descubrimiento automático**: el agente se anuncia por mDNS (`_gomonitor._tcp.local`) y la app
  lista los que encuentra. El ingreso manual de IP se mantiene como método principal.
- **Raspberry Pi con sensores**: recolectores para sensores reales (temperatura, humedad, presión)
  sobre la misma interfaz que hoy usa el recolector simulado.
- **Bluetooth**: transporte alternativo para cuando no hay ninguna red Wi-Fi.

## Documentación del proyecto

| Archivo | Contenido |
|---|---|
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Contrato REST y WebSocket. |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Decisiones de diseño y su porqué. |
| [docs/PROGRESS.md](docs/PROGRESS.md) | Estado, hitos y qué falta. |
| [docs/SPEC.md](docs/SPEC.md) | Requisito original. |
| [agent/README.md](agent/README.md) | Configuración y estructura del agente. |
| [android/README.md](android/README.md) | Compilación y estructura de la app. |
