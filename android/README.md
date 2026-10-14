# GoMonitor Mobile

App Android nativa (Kotlin + Jetpack Compose) que se conecta a GoMonitor Agent por la red local.
El contrato con el agente está en [../docs/PROTOCOL.md](../docs/PROTOCOL.md).

- Identificador: `com.fabapps.gomonitor`
- Android 8.0 (API 26) o superior
- Sin servicios externos: no usa Internet, cloud ni CDN

## Compilar e instalar

Requiere JDK 17 y el Android SDK. Usar siempre el wrapper del proyecto:

```bash
./gradlew assembleDebug      # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # instala en el teléfono conectado por adb
```

Para instalar sin adb, copiar el APK al teléfono y abrirlo (hay que permitir la instalación desde
orígenes desconocidos).

## Pruebas

```bash
./gradlew testDebugUnitTest  # pruebas en la JVM, sin teléfono
./gradlew lintDebug
make -C .. check-e2e         # la capa de red de la app contra un agente real
```

No se usa el emulador (ver D8 en [../docs/DECISIONS.md](../docs/DECISIONS.md)). Lo que solo se
puede ver en pantalla se prueba en un teléfono físico.

## Estructura

```text
app/src/main/java/com/fabapps/gomonitor/
├── core/   Lógica sin dependencias de Android: modelos del protocolo, validación, estados de
│           conexión, reconexión, formato de unidades, transformación del historial.
├── data/   OkHttp, almacenamiento de conexiones (DataStore) y estado de la red del teléfono.
└── ui/     Pantallas en Compose, tema y ViewModel.
```

Todo lo que está en `core/` se prueba en la JVM. `ConnectionManager` recibe la red como una
interfaz (`AgentTransport`), así que la conexión y la reconexión se prueban con tiempo virtual y
un transporte falso, y de punta a punta con el transporte real contra el agente.

## Tráfico sin TLS

El agente habla HTTP y WebSocket sin cifrar, así que la app permite tráfico en texto claro
(`usesCleartextTraffic` y `res/xml/network_security_config.xml`). Android no permite limitar ese
permiso a rangos de IP privadas, solo a dominios, por eso se habilita en general. Es adecuado para
una red privada de confianza; para redes no confiables el agente y la app deben pasar a HTTPS y WSS.

## Permisos

| Permiso | Para qué |
|---|---|
| `INTERNET` | Conectarse al agente en la red local. |
| `ACCESS_NETWORK_STATE` | Saber si el teléfono está en Wi-Fi y en qué subred, para explicar por qué falla una conexión. |

Las conexiones guardadas, token incluido, quedan en el almacenamiento privado de la app y están
excluidas de las copias de seguridad.
