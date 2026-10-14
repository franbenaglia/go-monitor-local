# Progreso

Se actualiza al cerrar cada hito y al terminar cada sesión. El detalle de lo hecho está en
`git log`; acá va el estado y lo que no se deduce del código.

## Estado actual

- **Último hito cerrado:** M0, harness y protocolo.
- **Próximo paso:** M1, esqueleto del agente y configuración.
- **Código existente:** ninguno todavía. `make check` pasa sin verificar nada.

## Entorno verificado (2026-10-13)

- Go 1.27.2, linux/amd64.
- JDK 17, Android SDK en `~/Android/Sdk` con plataformas 34 a 36 y build-tools hasta 37.
- No usar el emulador de Android: tilda la máquina (D8). Las pruebas en dispositivo se hacen con
  el teléfono físico por adb; pedirle a Francisco que lo conecte cuando haga falta.
- Gradle del sistema es 7.4.2 (viejo): usar siempre el wrapper del proyecto. Hay distribuciones
  8.x ya descargadas en `~/.gradle/wrapper/dists`.

## Hitos

Un hito se marca solo cuando `make check` pasa con su código incluido.

### Agente

- [x] **M0** Harness: repo, `CLAUDE.md`, decisiones, protocolo, `make check`.
- [ ] **M1** Módulo Go, configuración (flags > entorno > YAML > valores por defecto), logging,
      arranque y apagado ordenado.
- [ ] **M2** Interfaz `Collector`, recolector `system`, recolector `simulated`, cálculo de
      velocidad de red, selección de procesos.
- [ ] **M3** Almacenamiento SQLite: esquema, escritura, consultas agrupadas, retención.
- [ ] **M4** API REST completa con autenticación, límites y errores.
- [ ] **M5** WebSocket: autenticación, difusión, latido, límites, clientes lentos.
- [ ] **M6** Detección de IP privadas y mensaje de inicio; compilación para Linux, Windows y ARM;
      `go test -race`.

### App Android

- [ ] **M7** Proyecto Kotlin + Compose que compila; tema oscuro; cleartext permitido.
- [ ] **M8** Conexiones guardadas: alta, edición, baja, validación, "Probar conexión".
- [ ] **M9** Gestor de conexión: máquina de estados, WebSocket, reconexión con backoff.
- [ ] **M10** Dashboard: tarjetas de sistema y tarjetas genéricas por serie.
- [ ] **M11** Historial con selector de período y de series.
- [ ] **M12** Procesos con orden por CPU o memoria.
- [ ] **M13** Verificación de punta a punta en el teléfono físico por adb contra el agente local
      (requiere que Francisco conecte el celular).

### Cierre de la v1

- [ ] **M14** README principal con los 16 puntos de la sección 12 de `SPEC.md`.
- [ ] **M15** Prueba en teléfono físico con router y con hotspot (la hace Francisco).

### Después de la v1

- [ ] **M16** Descubrimiento mDNS (`_gomonitor._tcp.local`) con `NsdManager`.
- [ ] **M17** Raspberry Pi: compilación ARM probada en hardware y primer recolector de sensor real.

## Criterios de aceptación (sección 13 de `SPEC.md`)

| Criterio | Estado | Cómo se verificó |
|---|---|---|
| El agente funciona en una computadora | pendiente | |
| Recopila métricas reales cada cinco segundos | pendiente | |
| Almacena historial en SQLite | pendiente | |
| Muestra en consola su dirección IP local | pendiente | |
| El celular se conecta con IP, puerto y token | pendiente | |
| La app recibe métricas por WebSocket | pendiente | |
| El dashboard muestra CPU, RAM, discos, red y procesos | pendiente | |
| El historial se consulta mediante REST | pendiente | |
| La app detecta una desconexión | pendiente | |
| Se reconecta automáticamente | pendiente | |
| Funciona sin conexión a Internet | pendiente | |
| No utiliza ningún servidor central | pendiente | |
| Las pruebas y compilaciones finalizan correctamente | pendiente | |

## Problemas abiertos y cosas a recordar

- Ninguno todavía.
