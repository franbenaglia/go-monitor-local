# Progreso

Se actualiza al cerrar cada hito y al terminar cada sesión. El detalle de lo hecho está en
`git log`; acá va el estado y lo que no se deduce del código.

## Estado actual

- **Último hito cerrado:** M14. Agente y app están escritos, y `make check` pasa.
- **Próximo paso:** M13 y M15, que necesitan el teléfono: instalar el APK
  (`cd android && ./gradlew installDebug`) y recorrer la lista "Verificación en el teléfono" de
  más abajo. El 2026-10-13 Francisco instaló la app y conectó por Wi-Fi (ver criterios); falta
  que confirme cómo se ven las pantallas y recorrer el resto de la lista.
- **Código existente:** `agent/` y `android/`.

## Entorno verificado (2026-10-13)

- Go 1.27.2, linux/amd64.
- JDK 17, Android SDK en `~/Android/Sdk` con plataformas 34 a 36 y build-tools hasta 37.
- No usar el emulador de Android: tilda la máquina (D8). Las pruebas en dispositivo se hacen con
  el teléfono físico por adb; pedirle a Francisco que lo conecte cuando haga falta.
- Gradle del sistema es 7.4.2 (viejo): usar siempre el wrapper del proyecto (Gradle 9.8.1). Hay distribuciones
  8.x ya descargadas en `~/.gradle/wrapper/dists`.

## Hitos

Un hito se marca solo cuando `make check` pasa con su código incluido.

### Agente

- [x] **M0** Harness: repo, `CLAUDE.md`, decisiones, protocolo, `make check`.
- [x] **M1** Módulo Go, configuración (flags > entorno > YAML > valores por defecto), logging,
      arranque y apagado ordenado.
- [x] **M2** Interfaz `Collector`, recolector `system`, recolector `simulated`, cálculo de
      velocidad de red, selección de procesos.
- [x] **M3** Almacenamiento SQLite: esquema, escritura, consultas agrupadas, retención.
- [x] **M4** API REST completa con autenticación, límites y errores.
- [x] **M5** WebSocket: autenticación, difusión, latido, límites, clientes lentos.
- [x] **M6** Detección de IP privadas y mensaje de inicio; compilación para Linux, Windows y ARM;
      `go test -race`.

### App Android

- [x] **M7** Proyecto Kotlin + Compose que compila; tema oscuro; cleartext permitido.
- [x] **M8** Conexiones guardadas: alta, edición, baja, validación, "Probar conexión".
- [x] **M9** Gestor de conexión: máquina de estados, WebSocket, reconexión con backoff.
- [x] **M10** Dashboard: tarjetas de sistema y tarjetas genéricas por serie.
- [x] **M11** Historial con selector de período; grafica las series del sistema y los sensores.
- [x] **M12** Procesos con orden por CPU o memoria.
- [ ] **M13** Verificación de punta a punta en el teléfono físico por adb contra el agente local
      (requiere que Francisco conecte el celular).

M7 a M12 están marcados porque el código existe, compila, pasa lint sin errores y su lógica está
probada. Las pantallas (M8, M10, M11, M12) **no se vieron en un dispositivo**: puede haber
problemas de diseño o de comportamiento que solo aparecen en pantalla.

### Cierre de la v1

- [x] **M14** README principal con los 16 puntos de la sección 12 de `SPEC.md`.
- [ ] **M15** Prueba en teléfono físico con router y con hotspot (la hace Francisco).

### Después de la v1

- [ ] **M16** Descubrimiento mDNS (`_gomonitor._tcp.local`) con `NsdManager`.
- [ ] **M17** Raspberry Pi: compilación ARM probada en hardware y primer recolector de sensor real.

## Criterios de aceptación (sección 13 de `SPEC.md`)

| Criterio | Estado | Cómo se verificó |
|---|---|---|
| El agente funciona en una computadora | hecho | Ejecutado en esta máquina (Ubuntu 22.04, amd64); arranca, sirve y se apaga con SIGTERM. |
| Recopila métricas reales cada cinco segundos | hecho | Valores contrastados con `nproc`, `free` y `df`; el cliente de prueba recibió una muestra por ciclo. |
| Almacena historial en SQLite | hecho | Filas contadas en la base tras una ejecución real; pruebas de `internal/storage`. |
| Muestra en consola su dirección IP local | hecho | Mostró solo la IP del Wi-Fi y omitió loopback y los tres puentes de Docker. |
| El celular se conecta con IP, puerto y token | hecho | 2026-10-13: teléfono físico por Wi-Fi con router; conexión TCP establecida desde el teléfono al agente (vista con `ss`), app en primer plano y sin fallos en logcat. |
| La app recibe métricas por WebSocket | verificado sin dispositivo | `make check-e2e`: recibe muestras reales, una por ciclo. Falta el teléfono. |
| El dashboard muestra CPU, RAM, discos, red y procesos | sin verificar en dispositivo | El código compila; los datos llegan completos en `check-e2e`. La pantalla no se vio. |
| El historial se consulta mediante REST | verificado sin dispositivo | `curl` y `check-e2e`, que usa el mismo código que la pantalla de historial. La pantalla no se vio. |
| La app detecta una desconexión | verificado sin dispositivo | `check-e2e` detiene el agente y la app pasa a Reconectando; pruebas con tiempo virtual para la red que desaparece sin avisar. |
| Se reconecta automáticamente | verificado sin dispositivo | `check-e2e` reinicia el agente y la app vuelve a Conectado sola. |
| Funciona sin conexión a Internet | sin verificar | Ni el agente ni la app llaman a nada externo, pero no se probó con la red sin salida a Internet. |
| No utiliza ningún servidor central | hecho | El agente es un solo binario; la app solo se conecta a la IP que carga el usuario. |
| Las pruebas y compilaciones finalizan correctamente | hecho | `make check`: `go vet`, `go test -race`, 56 pruebas unitarias de la app, 3 de punta a punta, `assembleDebug`. Lint: 0 errores. |

## Verificación en el teléfono (M13 y M15)

Con el agente iniciado en la computadora (`agent/bin/gomonitor-agent -collectors system,simulated`
para ver también las tarjetas de sensores):

1. Conexiones: agregar una computadora con IP, puerto y token; probar los mensajes de validación
   con una IP mal escrita y un puerto fuera de rango.
2. "Probar conexión" con el token correcto y con uno incorrecto.
3. Conectar: el dashboard muestra hostname, sistema, uptime, CPU total y por núcleo, memoria,
   discos, subida y bajada, sensores simulados y procesos.
4. Historial: los cinco períodos; que los gráficos se lean y los huecos queden en blanco.
5. Procesos: ordenar por CPU y por memoria.
6. Detener el agente: la app pasa a Reconectando y muestra el motivo. Iniciarlo de nuevo: vuelve
   sola a Conectado.
7. Apagar el Wi-Fi del teléfono: tiene que notarlo en unos 15 segundos y explicar el motivo.
8. Cerrar y abrir la app con "Conexión automática" activada.
9. Girar el teléfono y cambiar de pestaña: la conexión no se corta.
10. Repetir la conexión con la computadora conectada al hotspot del teléfono y sin datos móviles.

## Problemas abiertos y cosas a recordar

- La app corrió en el teléfono de Francisco y conectó, pero las pantallas no fueron revisadas
  desde acá: falta su confirmación de que el dashboard, el historial y los procesos se ven bien.
- El agente solo se ejecutó en Linux amd64. Los binarios de Windows y ARM compilan (`make cross`)
  pero no se ejecutaron: falta probarlos en esas plataformas.
- Para probar el agente sin la app: `agent/bin/gomonitor-probe` (cliente WebSocket) y `curl`. Los
  comandos están en `agent/README.md`.
- En esta máquina el puerto de prueba usado fue 18080, para no chocar con otros servicios.
- El nombre de proceso viene del kernel y en Linux se corta a 15 caracteres (por ejemplo
  `Isolated Web Co` de Firefox). No es un error del agente.
- `used_percent` de disco puede diferir un punto de `df`, que descuenta los bloques reservados.
- Gradle: tras renombrar una carpeta de recursos, la compilación falló con "resource
  mipmap/ic_launcher not found" aunque el archivo estaba. Era una entrada mala en la caché de
  compilación. Se arregla con `./gradlew assembleDebug --no-build-cache --rerun-tasks`.
- La app mantiene la conexión mientras el proceso vive, también en segundo plano. No hay servicio
  en primer plano: Android puede cortar el socket con la pantalla apagada y la app reconecta al
  volver. Si hace falta monitoreo continuo en segundo plano, es trabajo nuevo.
- Las versiones de Gradle, AGP, Kotlin y Compose se copiaron de otro proyecto de esta máquina
  (`~/desarrollo/projects-ia/mesClaro`) porque ya estaban descargadas y funcionando juntas.
