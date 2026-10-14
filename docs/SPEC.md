Quiero que desarrolles una primera versión funcional de un sistema llamado **GoMonitor Local**, compuesto por:

1. Un agente escrito en Go que se ejecutará en una computadora.
2. Una aplicación Android desarrollada con React, Ionic y Capacitor.
3. Comunicación directa entre la computadora y el celular mediante la misma red Wi-Fi.
4. Sin servidor central, sin servicios cloud y sin necesidad de conexión a Internet.

## Arquitectura

El agente Go tendrá dos responsabilidades:

- recopilar las métricas de la computadora;
- actuar como servidor local HTTP y WebSocket.

La aplicación Android se conectará directamente a la dirección IP local de la computadora:

```text
Computadora
GoMonitor Agent
HTTP REST + WebSocket
192.168.1.50:8080
        │
        │ misma red Wi-Fi
        ▼
Celular Android
GoMonitor Mobile
```

Aunque técnicamente el agente exponga un servidor HTTP local, no debe existir un backend central ni una máquina adicional.

El sistema debe funcionar si:

- computadora y celular están conectados al mismo router;
- la computadora está conectada al hotspot Wi-Fi creado por el celular;
- la red no tiene acceso a Internet.

## Stack

### Agente de escritorio

- Go.
- `gopsutil` para recopilar métricas.
- `net/http` o un router HTTP liviano.
- Una biblioteca WebSocket mantenida y liviana.
- SQLite para guardar el historial.
- Preferentemente un driver SQLite puro en Go, sin requerir CGO.
- `log/slog` para logging estructurado.

### Aplicación Android

- React.
- TypeScript.
- Ionic.
- Capacitor.
- CSS propio o componentes Ionic.
- Biblioteca de gráficos que funcione completamente offline.
- No utilizar backend externo, Firebase, servicios cloud ni CDN.

Usar versiones estables compatibles entre sí. Si ya existe un proyecto en el directorio, inspeccionarlo antes de realizar cambios.

## Estructura sugerida

```text
gomonitor-local/
├── agent/
│   ├── cmd/
│   │   └── gomonitor-agent/
│   │       └── main.go
│   ├── internal/
│   │   ├── api/
│   │   ├── collector/
│   │   ├── config/
│   │   ├── models/
│   │   ├── storage/
│   │   └── websocket/
│   ├── configs/
│   │   └── config.example.yaml
│   ├── go.mod
│   ├── Makefile
│   └── README.md
├── mobile/
│   ├── src/
│   ├── android/
│   ├── capacitor.config.ts
│   ├── package.json
│   └── README.md
└── README.md
```

Puedes modificar la estructura si encuentras una alternativa más idiomática, pero el agente Go y la aplicación móvil deben quedar claramente separados.

# 1. Agente Go

Crear un ejecutable llamado:

```text
gomonitor-agent
```

Debe recopilar automáticamente:

- porcentaje total de CPU;
- porcentaje por núcleo;
- memoria total, usada y disponible;
- porcentaje de memoria usada;
- discos y particiones relevantes;
- espacio total, usado y disponible por disco;
- bytes enviados y recibidos;
- velocidad actual de subida y bajada;
- uptime;
- sistema operativo;
- arquitectura;
- hostname;
- procesos con mayor consumo de CPU;
- procesos con mayor consumo de memoria.

Por defecto, recopilar métricas cada 5 segundos.

Las velocidades de red deben calcularse comparando los contadores acumulados de dos muestras consecutivas. Manejar correctamente reinicios, valores negativos y cambios de interfaz.

No incluir interfaces loopback en el cálculo principal de red.

## Modelo de datos

Utilizar una estructura similar a:

```json
{
  "agent_id": "office-pc",
  "hostname": "francisco-pc",
  "operating_system": "linux",
  "architecture": "amd64",
  "timestamp": "2026-10-13T23:00:00Z",
  "uptime_seconds": 123456,
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
    {
      "pid": 1234,
      "name": "program",
      "cpu_percent": 12.5,
      "memory_percent": 4.2
    }
  ]
}
```

Enviar los 10 procesos con mayor consumo de CPU y los 10 con mayor consumo de memoria, eliminando duplicados.

La cantidad debe ser configurable.

Si un proceso desaparece durante la recopilación o no permite acceder a sus datos, ignorar ese proceso sin interrumpir el agente.

# 2. Servidor local integrado

El agente debe escuchar por defecto en:

```text
0.0.0.0:8080
```

La dirección y el puerto deben ser configurables.

Al iniciarse, debe detectar y mostrar claramente las direcciones IPv4 privadas disponibles:

```text
GoMonitor Agent iniciado

Conecta el celular a la misma red Wi-Fi.

Direcciones disponibles:
http://192.168.1.50:8080
http://192.168.0.24:8080
```

No mostrar interfaces loopback, Docker, VPN o interfaces virtuales como dirección recomendada, salvo que no exista otra alternativa.

## API REST

Implementar:

```text
GET /api/v1/health
GET /api/v1/status
GET /api/v1/history?period=15m
GET /api/v1/processes
GET /api/v1/system
GET /api/v1/network
GET /api/v1/disks
GET /api/v1/config/public
GET /api/v1/interfaces
GET /api/v1/ws
```

También aceptar períodos:

```text
15m
1h
6h
24h
7d
```

Validar todos los parámetros y aplicar límites a la cantidad de registros devueltos.

Las respuestas de error deben usar un formato consistente:

```json
{
  "error": {
    "code": "invalid_period",
    "message": "The requested history period is not valid"
  }
}
```

## WebSocket

La aplicación se conectará a:

```text
ws://IP_DE_LA_COMPUTADORA:8080/api/v1/ws
```

Después de abrir la conexión, el celular deberá enviar en menos de cinco segundos:

```json
{
  "type": "authenticate",
  "token": "local-development-token"
}
```

No colocar el token en la URL, porque podría aparecer en logs.

Si la autenticación es válida, el servidor responderá:

```json
{
  "type": "authenticated",
  "agent_id": "office-pc"
}
```

Después enviará una actualización cada vez que exista una nueva muestra:

```json
{
  "type": "metrics",
  "data": {}
}
```

También implementar:

```json
{
  "type": "ping"
}
```

y:

```json
{
  "type": "pong"
}
```

El servidor debe:

- cerrar conexiones no autenticadas;
- aceptar varios clientes simultáneamente;
- evitar que un cliente lento bloquee al agente;
- limitar tamaño y frecuencia de mensajes;
- detectar conexiones muertas;
- manejar correctamente la desconexión del celular;
- cerrar ordenadamente con `SIGINT` o `SIGTERM`.

# 3. Autenticación y seguridad local

Usar un token configurable.

Para REST:

```text
Authorization: Bearer <token>
```

Para WebSocket, usar el mensaje inicial de autenticación descrito anteriormente.

Configuración inicial:

```yaml
agent_id: "office-pc"
listen_address: "0.0.0.0"
port: 8080
collection_interval: "5s"
api_token: "change-this-token"
top_processes: 10
database_path: "./data/gomonitor.db"
retention: "7d"
log_level: "info"
```

Permitir configuración mediante:

1. flags;
2. variables de entorno;
3. archivo YAML;
4. valores predeterminados.

Documentar el orden exacto de prioridad.

No imprimir el token en logs.

Agregar:

- timeouts HTTP;
- límite de tamaño para solicitudes;
- validación de entradas;
- CORS limitado a los orígenes necesarios para Capacitor;
- protección contra conexiones WebSocket sin autenticar;
- límites razonables de conexiones.

No implementar ejecución remota de comandos.

# 4. Historial local

Guardar las métricas en SQLite en la computadora.

El celular consultará el historial directamente al agente mediante REST.

Guardar:

- CPU;
- memoria;
- red;
- discos;
- procesos;
- timestamp;
- información del equipo.

Agregar índices por fecha.

Retener siete días de historial de forma predeterminada. Ejecutar periódicamente una tarea de limpieza.

No guardar solamente un JSON opaco. Las métricas principales deben almacenarse en columnas consultables.

El historial debe seguir almacenándose aunque no haya ningún celular conectado.

# 5. Aplicación Android

Crear una aplicación llamada:

```text
GoMonitor Mobile
```

Identificador inicial:

```text
com.fabapps.gomonitor
```

La aplicación debe permitir configurar:

- nombre de la conexión;
- dirección IP de la computadora;
- puerto;
- token;
- conexión automática;
- intervalo de reconexión.

Ejemplo:

```text
Nombre: Computadora principal
IP: 192.168.1.50
Puerto: 8080
Token: local-development-token
```

Guardar la configuración localmente mediante Capacitor Preferences o un almacenamiento local apropiado.

No enviar estos datos a servicios externos.

## Pantallas

### Pantalla de conexiones

Mostrar:

- conexiones guardadas;
- botón para agregar una computadora;
- edición y eliminación;
- última conexión exitosa;
- estado conectado o desconectado;
- botón “Probar conexión”.

La primera versión debe permitir introducir manualmente la IP. Esto es obligatorio y no debe depender de descubrimiento automático.

### Dashboard

Mostrar:

- hostname;
- sistema operativo;
- arquitectura;
- estado conectado o desconectado;
- última actualización;
- uptime;
- CPU total;
- CPU por núcleo;
- memoria usada y total;
- porcentaje de memoria;
- uso de cada disco;
- velocidad de subida;
- velocidad de bajada;
- total enviado y recibido;
- procesos principales.

Usar tarjetas, barras de progreso y gráficos fáciles de leer.

### Historial

Mostrar gráficos de:

- CPU;
- memoria;
- subida;
- bajada.

Permitir seleccionar:

```text
15 minutos
1 hora
6 horas
24 horas
7 días
```

Los gráficos deben funcionar offline y no cargar scripts desde CDN.

### Procesos

Mostrar una tabla o lista con:

- PID;
- nombre;
- CPU;
- memoria.

Permitir ordenar por CPU o memoria.

# 6. Conexión y reconexión

La aplicación debe:

- probar primero `/api/v1/health`;
- abrir el WebSocket;
- autenticarse;
- mostrar datos en tiempo real;
- detectar desconexiones;
- reconectarse con backoff exponencial;
- detener el backoff en un máximo razonable;
- permitir reconexión manual;
- mostrar mensajes comprensibles;
- evitar múltiples WebSockets simultáneos;
- limpiar timers al cambiar de pantalla o cerrar la conexión.

Ejemplo de estados:

```text
Sin configurar
Conectando
Autenticando
Conectado
Reconectando
Sin conexión
Token incorrecto
Equipo no encontrado
```

Distinguir entre:

- dirección incorrecta;
- computadora inaccesible;
- token inválido;
- timeout;
- WebSocket cerrado;
- celular conectado a otra red.

# 7. Android y HTTP local

Como esta primera versión utilizará HTTP y WebSocket sin TLS dentro de una red privada, configurar Android para permitir tráfico local en texto claro.

Incluir los cambios necesarios en:

- `AndroidManifest.xml`;
- Network Security Config;
- configuración de Capacitor.

Solicitar únicamente los permisos necesarios.

Documentar claramente que esta configuración es apropiada para una prueba en red privada, pero que una versión expuesta a redes no confiables deberá usar HTTPS y WSS.

# 8. Descubrimiento automático opcional

Una vez que la conexión manual funcione correctamente, agregar descubrimiento mediante mDNS/NSD si puede implementarse sin perjudicar la estabilidad.

El agente puede anunciar:

```text
_gomonitor._tcp.local
```

con:

```text
name=office-pc
port=8080
agent_id=office-pc
version=1
```

La aplicación podría mostrar los agentes encontrados.

La entrada manual por IP siempre debe continuar disponible como método principal y de respaldo.

Si mDNS requiere código nativo de Capacitor, implementar un plugin pequeño en Kotlin o documentar esta funcionalidad como segunda etapa. No introducir una dependencia abandonada solamente para agregar descubrimiento.

# 9. Interfaz

Diseñar una interfaz moderna y oscura, con colores diferentes para:

- CPU;
- memoria;
- disco;
- subida;
- bajada;
- estado conectado;
- alertas.

Utilizar unidades legibles:

- B, KB, MB, GB y TB;
- B/s, KB/s y MB/s;
- segundos convertidos en días, horas y minutos.

La app debe ser responsive y funcionar correctamente en orientación vertical.

Agregar estados de carga, pantallas vacías y mensajes de error.

# 10. Pruebas

## Go

Crear pruebas para:

- recopilación de métricas;
- cálculo de velocidad de red;
- selección y deduplicación de procesos;
- autenticación REST;
- autenticación WebSocket;
- almacenamiento SQLite;
- consultas históricas;
- retención;
- validación de períodos;
- detección de direcciones IP privadas.

Ejecutar:

```bash
go test ./...
go vet ./...
```

Si es posible:

```bash
go test -race ./...
```

## Aplicación móvil

Crear pruebas para:

- validación de IP y puerto;
- almacenamiento de conexiones;
- estados de conexión;
- procesamiento de mensajes WebSocket;
- reconexión;
- formateo de bytes y uptime;
- transformación del historial para gráficos.

Ejecutar:

```bash
npm test
npm run build
npx cap sync android
```

Si el entorno tiene Android SDK configurado, ejecutar también:

```bash
cd android
./gradlew assembleDebug
```

# 11. Ejecución esperada

El agente debe poder iniciarse así:

```bash
cd agent
go run ./cmd/gomonitor-agent
```

O compilado:

```bash
make build
./bin/gomonitor-agent
```

La aplicación:

```bash
cd mobile
npm install
npm run build
npx cap sync android
npx cap open android
```

También agregar un modo web para pruebas durante el desarrollo:

```bash
npm run dev
```

La versión web debe permitir introducir la IP y conectarse al agente, teniendo en cuenta CORS.

# 12. README

Crear un README principal completo que explique:

1. arquitectura;
2. requisitos;
3. instalación;
4. inicio del agente;
5. compilación de la app;
6. conexión del celular;
7. cómo encontrar la IP local;
8. configuración del firewall;
9. uso con router Wi-Fi;
10. uso con hotspot del celular;
11. configuración del token;
12. solución de problemas;
13. pruebas;
14. compilación para Linux y Windows;
15. limitaciones de HTTP local;
16. evolución futura hacia HTTPS o Bluetooth.

Incluir ejemplos para permitir el puerto `8080` en firewalls habituales, sin desactivar por completo el firewall.

# 13. Criterios de aceptación

La versión inicial estará terminada cuando:

- el agente Go funcione en una computadora;
- recopile métricas reales cada cinco segundos;
- almacene historial en SQLite;
- muestre en consola su dirección IP local;
- el celular se conecte introduciendo IP, puerto y token;
- la app reciba métricas por WebSocket;
- el dashboard muestre CPU, RAM, discos, red y procesos;
- el historial se consulte mediante REST;
- la aplicación detecte una desconexión;
- se reconecte automáticamente;
- funcione sin conexión a Internet;
- no utilice ningún servidor central;
- las pruebas y compilaciones finalicen correctamente.

# Forma de trabajo

Antes de modificar archivos:

1. inspecciona el directorio actual;
2. comprueba si ya existe algún proyecto;
3. no elimines ni sobrescribas archivos ajenos;
4. presenta brevemente la arquitectura que implementarás.

Después:

1. crea el agente Go;
2. prueba la API y WebSocket localmente;
3. verifica que las métricas sean reales;
4. crea la aplicación Ionic/Capacitor;
5. conecta la aplicación al agente;
6. ejecuta pruebas y compilaciones;
7. corrige los errores encontrados;
8. entrega un resumen de archivos creados, comandos y limitaciones.

No entregues solamente pseudocódigo, ejemplos aislados o una descripción. Implementa una primera versión funcional y ejecutable.