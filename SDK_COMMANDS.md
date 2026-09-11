# SIYI Datalink SDK — catálogo de comandos y resultado de pruebas reales

Transcrito de `MK15_User_Manual_v1_9.pdf`, sección **4.8 "SIYI Datalink SDK"**
(páginas 107-117: 4.8.1 Format, 4.8.2 Commands, 4.8.3 Communication
Interface, 4.8.4 SDK CRC16 Check Code). Este documento es el catálogo
completo de comandos del protocolo — `PROTOCOL.md` sigue siendo la
referencia de arquitectura/decisiones del proyecto; este archivo es el
detalle exhaustivo comando-por-comando, con qué se ha probado contra el
handset real y qué no.

**Todas las pruebas de esta tabla se hicieron el 2026-09-10** contra un
MK15 real conectado por `adb` (`46bed092`), hablando directamente con
`/dev/ttyHS0` a 115200 baudios (ver `PROTOCOL.md` para el hallazgo de por
qué hace falta configurar el puerto a mano — `iuclc`/`ixon`/`ixoff`/`ixany`
vienen activados por defecto y corrompen bytes). Metodología: por cada
comando, se generó la petición byte a byte (verificando su CRC16 contra el
algoritmo de la sección 4.8.4 antes de enviarla), se mandó por `adb shell
printf ... > /dev/ttyHS0` con una captura `cat` de fondo, y se verificó la
CRC16 de la respuesta recibida.

## 4.8.1 Formato de trama

| Campo | Índice | Bytes | Descripción |
|---|---|---|---|
| `STX` | 0 | 2 | `0x55 0x66`, marca de inicio |
| `CTRL` | 2 | 1 | `0`=need_ack (pide ack), `1`=ack_pack (es un ack), `2-7` reservado |
| `Data_len` | 3 | 2 | Longitud de `DATA` en bytes, little-endian |
| `SEQ` | 5 | 2 | Nº de secuencia (0~65535), little-endian |
| `CMD_ID` | 7 | 1 | ID del comando |
| `DATA` | 8 | `Data_len` | Datos del comando |
| `CRC16` | — | 2 | CRC16 de toda la trama excepto el propio CRC, little-endian |

CRC16: polinomio `0x1021`, inicial `0x0000`, sin reflejar, sin XOR final
— mismo algoritmo ya implementado en `Crc16.java` y reutilizado tal cual
por `sdk/SdkFrame.java`. Confirmado independientemente contra el código C
de referencia de la sección 4.8.4 del manual.

## Tabla resumen de comandos

| `CMD_ID` | Nombre | Tipo | Código propio | Probado en hardware real | Resultado |
|---|---|---|---|---|---|
| `0x40` | Request Hardware ID | Lectura | — | ✅ | **OK** — ID `6801131040` |
| `0x16` | Request System Settings | Lectura | — | ✅ | **OK** — ver detalle |
| `0x17` | Send System Settings Commands to Ground Unit | **Escritura** | — | ⛔ no probado (deliberado) | — |
| `0x42` | Request Channel Data | Lectura | `sdk/ChannelData.java` | ✅ | ❌ **sin respuesta** (0 bytes) |
| `0x43` | Request Datalink Status | Lectura | — | ✅ | **OK** |
| `0x44` | Request Image Transmission Link Status | Lectura | — | ✅ | **OK** |
| `0x47` | Request Firmware Version | Lectura | `sdk/FirmwareVersion.java` | ✅ | **OK** — `rcVersion=5.5.4` |
| `0x48` | Request All Channel Mappings | Lectura | — | ✅ | **OK** |
| `0x49` | Request A Specific Channel Mapping | Lectura | — | ✅ | **OK** — CH1→Joystick J1 |
| `0x4A` | Send Channel Mapping to Ground Unit | **Escritura** | — | ⛔ no probado (deliberado) | — |
| `0x4B` | Request All Channel Reverse | Lectura | — | ✅ | **OK** — ninguno invertido |
| `0x4C` | Request Channel Reverse | Lectura | — | ✅ | **OK** — CH1 normal |
| `0x4D` | Send Channel Reverse to Ground Unit | **Escritura** | — | ⛔ no probado (deliberado) | — |

**9 de 10 comandos de solo lectura probados responden con datos válidos y
CRC correcta.** El único que no responde nada es precisamente el que
necesitamos para el objetivo real del proyecto (`0x42`, los 16 canales del
joystick) — ver su sección para las hipótesis abiertas.

Esta tabla cubre solo los comandos **documentados en el manual**
(sección 4.8.2, `0x40`-`0x4D`). Hay otros ~20 `CMD_ID` que la app oficial
usa pero que el manual no menciona — ver "Catálogo de `CMD_ID` que usa la
app y que no están en el manual" más abajo (hallados por decompile con
`jadx`, ninguno probado aún contra hardware real).

Los 3 comandos de **escritura** (`0x17`, `0x4A`, `0x4D`) se dejaron
deliberadamente sin probar: modifican estado persistente del equipo real
(vinculación/bind y baudrate de telemetría, mapeo de canales físicos,
inversión de canales) que en este proyecto termina controlando un
vehículo real — no parecía prudente tocarlos sin confirmación explícita.
Si se quieren probar, decidme y lo hacemos con cuidado (por ejemplo
leyendo el valor actual con su comando de lectura hermano y reenviando
exactamente ese mismo valor, para no cambiar nada de verdad).

---

## Detalle por comando

### `0x40` — Request Hardware ID (lectura)

Sin payload de petición. ACK: `hardware_id[12]` (string ASCII).

```
Ejemplo del manual — send:     55 66 01 00 00 00 00 40 81 9c
Ejemplo del manual — response: 55 66 02 0C 00 09 00 40 36 38 30 31 31 33 30 31 31 31 00 00 7b 8b
```
CRC de ambos ejemplos verificada ✅ (coincide con nuestro algoritmo).

**Prueba real (2026-09-10):**
```
Send:     55 66 01 00 00 00 00 40 81 9c
Response: 55 66 02 0c 00 05 00 40 36 38 30 31 31 33 31 30 34 30 00 00 08 53
```
CRC de la respuesta real verificada ✅. `DATA` decodifica a ASCII
`"6801131040"` — coincide con el `deviceName=6801131040` visto por
logcat de la propia app del fabricante en la sesión anterior.

---

### `0x16` — Request System Settings (lectura)

Sin payload de petición documentado (el manual no trae ejemplo para
este comando). ACK: `match` (estado de bind: 0=Start, 1-2=Binding,
3=Finished), `Baud_type` (baudrate de telemetría, enum 0-6),
`Joy_type` (modo de joystick: 0=Mode1, 1=Mode2, 2=Mode3, 3=Custom),
`Rc_bat` (batería del equipo terrestre, unidades de 0.1V).

**Prueba real (2026-09-10)** — payload de petición vacío, CRC calculada
por nosotros (`b2 a6`) al no haber ejemplo en el manual:
```
Send:     55 66 01 00 00 00 00 16 b2 a6
Response: 55 66 02 04 00 06 00 16 00 05 01 49 59 d0
```
CRC verificada ✅. `DATA` = `00 05 01 49` → `match=0x00`,
`Baud_type=0x05` (BAUD_115200), `Joy_type=0x01` (Mode 2),
`Rc_bat=0x49=73` → 7.3V de batería interna del mando. Nótese que este
"baudrate de telemetría" es un parámetro lógico del enlace RF
mando↔air-unit, **no** tiene relación con el baudrate 115200 del propio
puerto serie `ttyHS0` que usamos para hablar este protocolo — coinciden
en valor por casualidad.

---

### `0x17` — Send System Settings Commands to Ground Unit (**escritura**) ⛔ no probado

Payload: `match`, `Baud_type`, `Joy_type`, `reserved`. ACK: `sta`
(`1`=ok, negativo=error). Sin ejemplo en el manual.

**No probado deliberadamente**: escribe el estado de *bind* del enlace
RF y el baudrate de telemetría del equipo terrestre. Un valor de
`match` mal elegido podría iniciar un proceso de re-vinculación real.

---

### `0x42` — Request Channel Data (lectura) — ❌ el único que falla

Payload: `freq` (0=OFF .. 7=100Hz). ACK: 16 × `int16_t` LE, un canal
cada uno (rango de fábrica ~1050-1950). Ya implementado en
`sdk/ChannelData.java`.

```
Ejemplo del manual — send 4Hz: 55 66 01 01 00 00 00 42 02 B5 C0
Ejemplo del manual — send OFF: 55 66 01 01 00 00 00 42 00 F7 E0
Ejemplo del manual — response (2Hz): 55 66 00 20 00 99 00 42 ...32 bytes... FF 88
```
CRC de ambos *send* verificada ✅. La CRC del *response* del manual
**no cuadra** (calculada `0x8be2` vs. impresa `0x88ff` — errata de
transcripción, ver `ChannelData.java`).

**Prueba real (2026-09-10)**, con el puerto ya correctamente configurado
(mismo que acaba de responder perfectamente a `0x47` segundos antes,
ver más abajo):
```
Send:     55 66 01 01 00 00 00 42 02 b5 c0   (4Hz)
Response: (0 bytes, timeout de 4s)
```
También probado a 100Hz (`freq=7`) y con hasta 6s de espera en sesiones
anteriores — mismo resultado, silencio total. No es un problema de
transporte (el mismo enlace, en la misma sesión, acaba de demostrar que
funciona con otros 9 comandos). Hipótesis sin confirmar, de
`PROTOCOL.md`:
1. Necesita un paso de activación aparte del toggle "UART" — el propio
   manual avisa: *"Enabling RC channel output will affect telemetry
   communication as they are using the same port"*, lo que sugiere un
   interruptor específico, no solo elegir el tipo de conexión.
2. Este firmware concreto (producto `0x68`, RC v5.5.4) puede no
   implementarlo aunque esté en el manual genérico de la gama.
3. Puede necesitar una air unit emparejada para tener canales reales
   que reportar (este banco de pruebas no tiene ninguna).
4. ~~Payload vacío en vez de 1 byte `freq`~~ — **descartada, probada
   contra hardware real (2026-09-10)**. La propia app UniGCS pide
   `0x42` con `Data_len=0` (sin el byte `freq` documentado); se envió
   exactamente `55 66 01 00 00 00 00 42 c3 bc` (CRC verificada) con el
   puerto recién confirmado sano (`0x47` respondió limpio justo antes)
   — **0 bytes de respuesta, mismo silencio que con el payload
   documentado**. No es la pieza que falta.
5. **`0x42` no se usa en la app como un "dame los 16 canales"
   repetible — se llama una sola vez, como primer paso al pulsar
   "emparejar" en la pantalla de vinculación RF** (hallazgo del
   decompile con jadx, ver sección de abajo: analítica `PAIR_START`,
   `BindingState` pasa de `UNBOUND` a `BINDING` —
   `InterConnectionViewModel`/`ui/interconnection/f0.java`,
   `cVar.y()`). **Probado contra hardware real (2026-09-10) sin
   confirmar ningún efecto lateral**: se leyó `0x16` (que expone el
   campo `match` = estado de bind) antes y después de mandar `0x42`
   (documentado, 4Hz) — `match` se quedó en `0x00` en ambos casos, sin
   cambio. También se escuchó el puerto 8s completos tras el envío por
   si la respuesta llegaba de forma asíncrona/retrasada — silencio
   total igualmente. Osea: mandar `0x42` en aislado, sin más contexto
   de UI/handshake y sin una air unit real presente, no produce ningún
   efecto observable (ni ACK, ni cambio de estado de bind). Sigue
   siendo el candidato más plausible (necesita una air unit real
   intentando emparejar al mismo tiempo, algo que no se puede simular
   solo con `adb`), pero ya no es una hipótesis "sin probar" — el
   siguiente paso real es repetir esto con una air unit SIYI física
   presente, no seguir aislando `0x42` por `adb`.

**Estado a 2026-09-10 (sesión con hardware conectado, `46bed092`)**:
las hipótesis 1, 2 y 4 se han descartado o quedado sin evidencia de
progreso; la 3/5 (air unit emparejada) es la única que queda en pie y
no se puede seguir investigando sin una air unit física.

### 🔑 Hallazgo mayor (debug en vivo, mismo día): el protocolo del SDK corre también sobre `/dev/ttyHS1` a 230400 — no solo `/dev/ttyHS0`

Con el mando todavía conectado, se lanzó la app real (`biz.siyi.remotecontrol`,
actividad `biz.siyi.pilot.app.HomeActivity` — este es el APK "UniGCS" que
descompilamos; su paquete Android real es `biz.siyi.remotecontrol`, mismo
que "SIYI TX", confirmado con `pyaxmlparser`: `package: biz.siyi.remotecontrol,
version: 3.1.6`) y se capturó `adb logcat` en vivo mientras el usuario
navegaba la UI real hasta la pantalla de **datos de canal**
(`ChannelViewModel`). Log completo guardado localmente en
`docs/logs/unigcs_live_logcat_2026-09-10.txt` (no versionado — son ~1.6MB
de logcat con IDs de dispositivo; se han extraído aquí los fragmentos
relevantes).

**Dos hallazgos concretos:**

1. **Sí hay datos de canal reales y en vivo — pero no vienen por el `0x42`
   documentado.** En cuanto se abrió la pantalla de canales
   (`SystemSettingViewModel`/`ChannelViewModel`: `requestAllChannelValue`),
   empezó un stream continuo (~20ms de periodo, 627 frames en ~24s) de
   `SIYIRemoteControlParser: parseRcCmd, cmdId:1 data:...`, 32 bytes de
   payload = 16×`int16` LE:
   ```
   cmdId:1 data:DC05DC05DC05DC051A041A04DC051A041A041A041A04DC05DC05E8031A041A04
   → CH1-16: 1500 1500 1500 1500 1050 1050 1500 1050 1050 1050 1050 1500 1500 1000 1050 1050
   ```
   Coincide exactamente con el mapeo de canales ya confirmado por `0x48`
   (CH1-4 joystick J1-J4 centrados en 1500 con los sticks sin tocar,
   CH5-7 interruptores de 3 posiciones en un valor de detent plausible,
   etc.) — son valores reales y coherentes, no basura. (Los sticks no se
   movieron durante la captura, por eso el valor es idéntico en las 627
   apariciones — no se pudo confirmar variación en vivo, pero la
   estructura por sí sola ya es una prueba fuerte.)

2. **De dónde sale, confirmado por decompile**:
   `biz/siyi/pilot/rcuservice/RemoteControlService.java`, en `onCreate()`,
   construye exactamente **`t5.k`** — la misma clase que implementa
   *todos* los `CMD_ID` de este documento, documentados y no
   documentados — pero pasándole un `SerialPort("/dev/ttyHS1", 230400)`,
   **no** `/dev/ttyHS0` a 115200:
   ```java
   dVar.f17194f = "/dev/ttyHS1";
   dVar.f17191c = 230400;
   ...
   aVar2.f17186a = new t5.k(new t5.i(tVar), gVar, tVar);
   ```
   Es decir: el protocolo "SIYI Datalink SDK" (framing `55 66`, CRC16,
   catálogo de `CMD_ID`) **no es exclusivo de la interfaz "externa"
   documentada en la sección 4.8.3 del manual** (`/dev/ttyHS0`,
   115200) — la propia app lo habla también, internamente, contra
   `/dev/ttyHS1` a 230400. Esto explica retroactivamente por qué las
   capturas en crudo de `ttyHS1` de las primeras sesiones del proyecto
   (2026-09-07/08, antes de conocer siquiera la sección 4.8) solo veían
   un heartbeat: la app estaba corriendo pero nadie tenía abierta la
   pantalla de canales, así que `t5.k` no estaba siendo usado para pedir
   canales en ese momento — no es que el canal de datos no exista, es
   que hay que "pedirlo" desde la UI para que empiece a fluir.

   **Sin confirmar todavía**: si los bytes exactos que van por el cable
   en `ttyHS1` son literalmente tramas `55 66...` (como las que ya
   probamos a mano en `ttyHS0`) o si hay una capa de framing adicional
   por debajo — las líneas de log `WriteTask` muestran hex que empieza
   por `AA 09...`, no por `55 66...` (ej.
   `AA090200ADEA03D0101060DC8A`), así que **hace falta una captura en
   crudo de `/dev/ttyHS1` para saber si eso es la trama completa (en
   cuyo caso el protocolo real de `ttyHS1` no es literalmente el mismo
   `55 66` del manual, aunque la usen la misma clase `t5.k` y el mismo
   CRC16) o si hay una re-codificación intermedia para el log**.

**Actualización 2026-09-10/11 — resuelto, y con un resultado mejor de lo
esperado**: se probó exactamente esto. El puerto sí admite una segunda
apertura concurrente (se pudo leer sin errores mientras la app lo tenía
abierto). Pero mandar la petición `55 66`/`CMD_ID 0x42` contra `ttyHS1`
**no obtuvo ninguna respuesta `55 66`** — en cambio, la captura sí devolvió
tráfico real: tramas del **protocolo antiguo `AA 0A 02`** (el que este
proyecto documentó al principio, antes de encontrar la sección 4.8 del
manual). Con una captura más larga (25s) mientras se reabría la pantalla
de "datos de canal" de la app, aparecieron tramas `type=0x20, sub_id=0x01`
(45 bytes) — **exactamente** el `FrameCatalog.CHANNELS` que el propio
proyecto ya tenía definido en `android/.../protocol/FrameCatalog.java`
desde el principio — con los 16 canales reales y coherentes. Es decir:
`ttyHS1` **no habla el framing `55 66` del manual** (aunque lo construya
la misma clase `t5.k` por dentro, ver más abajo) — habla el protocolo
`AA 0A 02` original de este proyecto, y ese protocolo **sí funciona, en
vivo, ahora mismo**, sin air unit y sin ninguno de los bloqueos de `0x42`.
Detalle completo, con los bytes reales capturados y la corrección de un
bug real que esto encontró en `FrameParser.java`, en **PROTOCOL.md**
("Resuelto, 2026-09-10/11" en la sección del catálogo de tramas). Esto no
cierra la investigación de `0x42`/SDK por sí solo (sigue sin resolverse
por qué no responde), pero para el objetivo real del proyecto (canales del
joystick) **ya no es el camino bloqueante** — `ttyHS1` con el protocolo
viejo lo es, y ya está funcionando.

**Actualización 2026-09-11 — bytes reales del `CMD_ID 0x01` (start/stop
streaming) capturados vía `WriteTask`**: con el mando conectado, captura
en paralelo de `adb logcat` + captura cruda de `/dev/ttyHS1` mientras se
abría la pantalla de canales de SIYI TX tres veces. Confirmado que las
tramas de *escritura* en `ttyHS1` usan una sincronización distinta a la
de lectura: `AA 09 02` (no `AA 0A 02`). Formato:
`AA 09 02 01 <ctr:2> <01=start|00=stop> D0 10 10 01 01 <CRC16 LE>`, CRC
verificado a mano con el mismo algoritmo de `Crc16.java` sobre las 5
muestras capturadas. Detalle byte a byte en **PROTOCOL.md**, sección
"Trigger identified by decompile" (ahora "Confirmed on the wire"). Aún no
implementado en la app propia — el siguiente paso es que
`SiyiSerialReader` (hoy solo lectura) también pueda escribir este frame
al abrir el puerto, para no depender de que un humano abra la pantalla de
canales de SIYI TX ni una sola vez.

---

### `0x43` — Request Datalink Status (lectura)

Sin payload. ACK: `freq` (uint16), `pack_loss_rate` (uint8),
`real_pack`/`real_pack_rate` (uint16), `data_up`/`data_down` (uint32,
byte/s).

```
Ejemplo del manual: send 55 66 01 00 00 00 00 43 e2 ac
                     resp 55 66 02 0F 00 01 00 43 02 00 00 02 00 02 00 00 00 00 00 00 00 00 00 2E 5C
```
CRC de ambos verificada ✅.

**Prueba real (2026-09-10):**
```
Send:     55 66 01 00 00 00 00 43 e2 ac
Response: 55 66 02 0f 00 07 00 43 02 00 00 02 00 02 00 00 00 00 00 00 00 00 00 ca 5c
```
CRC verificada ✅. `freq=2`, `pack_loss_rate=0`, `real_pack=2`,
`real_pack_rate=2`, `data_up=0`, `data_down=0` — estructura y valores
prácticamente idénticos al ejemplo del propio manual.

---

### `0x44` — Request Image Transmission Link Status (lectura)

Sin payload. ACK: 9 × `int32_t` (`signal`, `inactive_time`,
`upstream`, `downstream`, `txbandwidth`, `rxbandwidth`, `rssi`, `freq`
en MHz, `channel`).

**Prueba real (2026-09-10):**
```
Send:     55 66 01 00 00 00 00 44 05 dc
Response: 55 66 02 24 00 08 00 44 00...00 90 15 00 00 68 00 00 00 49 32
```
CRC verificada ✅. Todos los campos a 0 salvo `freq=0x1590=5520` (MHz)
y `channel=0x68=104` — valores plausibles de un canal RF de la banda
alta de 5GHz que este equipo usa para vídeo, incluso sin una air unit
emparejada (probablemente el último canal escaneado/por defecto).
`rssi=0` es coherente con "sin enlace activo".

---

### `0x47` — Request Firmware Version (lectura) — ✅ referencia de que el transporte funciona

Ya implementado en `sdk/FirmwareVersion.java` y documentado en detalle
en `PROTOCOL.md`. Repetido aquí como referencia porque es el comando
que se usó para validar el transporte antes de probar todo lo demás.

**Prueba real (2026-09-10), repetida en esta misma sesión:**
```
Send:     55 66 01 00 00 00 00 47 66 ec
Response: 55 66 02 10 00 0d 00 47 04 05 05 68 00 00 00 00 06 02 00 56 00 00 00 00 d2 7b
```
CRC verificada ✅. `rcVersion=5.5.4 (producto 0x68)`,
`groundVersion=0.2.6 (producto 0x56)`.

---

### `0x48` — Request All Channel Mappings (lectura)

Sin payload. ACK: 16 × (`type` uint8, `entity_id` uint8) — a qué
control físico está mapeado cada canal RC.

**Prueba real (2026-09-10):**
```
Send:     55 66 01 00 00 00 00 48 89 1d
Response: 55 66 02 20 00 09 00 48 00 00 00 01 00 02 00 03 05 00 05 01 05 02 01 00 01 01 01 02 01 03 00 04 00 05 02 01 01 00 01 01 a9 fa
```
CRC verificada ✅. Decodifica (según la tabla "MK15 Handheld Ground
Station" del manual, página 112) a: CH1-4 = Joystick J1-J4, CH5-7 =
interruptores de 3 posiciones SA/SB/SC, CH8-11 = botones A-D, CH12-13 =
diales LD/RD — coherente con el mando físico real.

---

### `0x49` — Request A Specific Channel Mapping (lectura)

Payload: `rc_ch` (canal 1-16). ACK: `rc_ch`, `type`, `entity_id`.

**Prueba real (2026-09-10)**, canal 1, CRC calculada por nosotros
(`2c 2c`, sin ejemplo equivalente en el manual para este canal):
```
Send:     55 66 01 01 00 00 00 49 01 2c 2c
Response: 55 66 02 03 00 0a 00 49 01 00 00 85 82
```
CRC verificada ✅. `CH1 → type=0 (Joystick), entity_id=0 (J1)` —
coherente con `0x48` de arriba.

---

### `0x4A` — Send Channel Mapping to Ground Unit (**escritura**) ⛔ no probado

Payload: `rc_ch`, `type`, `entity_id`. ACK: `rc_ch`, `sta`.

**No probado deliberadamente**: reasigna en caliente a qué control
físico responde un canal RC — con el vehículo dependiendo de CH1/CH3
para dirección/tracción, un valor equivocado podría desconectar
temporalmente el control real del joystick correcto.

---

### `0x4B` — Request All Channel Reverse (lectura)

Sin payload. ACK: 16 × `int8_t` (`1`=normal, `-1`=invertido).

**Prueba real (2026-09-10):**
```
Send:     55 66 01 00 00 00 00 4b ea 2d
Response: 55 66 02 10 00 0b 00 4b 01 01 01 01 01 01 01 01 01 01 01 01 01 01 01 01 38 39
```
CRC verificada ✅. Los 16 canales en `1` (normal) — ninguno invertido
en este mando, a diferencia del ejemplo del manual (que tenía CH2
invertido).

---

### `0x4C` — Request Channel Reverse (lectura)

Payload: `rc_ch` (1-16). ACK: `rc_ch`, `reverse`.

**Prueba real (2026-09-10)**, canal 1, CRC calculada por nosotros
(`d9 d3`):
```
Send:     55 66 01 01 00 00 00 4c 01 d9 d3
Response: 55 66 02 02 00 0c 00 4c 01 01 e3 a9
```
CRC verificada ✅. `CH1 = normal` — coherente con `0x4B`.

---

### `0x4D` — Send Channel Reverse to Ground Unit (**escritura**) ⛔ no probado

Payload: `rc_ch`, `reverse`. ACK: `rc_ch`, `sta`.

**No probado deliberadamente**, mismo motivo que `0x4A` y `0x17`:
cambia en caliente el sentido de un canal real (p. ej. invertir CH1
invertiría la dirección).

---

## 4.8.3 Interfaces de comunicación

El manual documenta 4 interfaces posibles para este SDK, elegibles
desde la app "SIYI TX":

1. **Puerto serie UART** — `/dev/ttyHS0`, 115200 baudios (la que usa
   este proyecto).
2. **USB COM** (USB-a-serie) — mismo baudrate que el datalink.
3. **Bluetooth**.
4. **Puerto de actualización RC del MK15 / puerto USB-C del MK32**
   (puerto serie virtual sobre USB).

## Ingeniería inversa adicional: APK de UniGCS (decompile completo con jadx)

⚠️ **Aclaración de nombres**: el fichero se llama
`UniGCS_prod_mk15_32_release_3_1_6_*.apk`, pero **su paquete Android real
es `biz.siyi.remotecontrol`** (confirmado con `pyaxmlparser`:
`package: biz.siyi.remotecontrol`, `version: 3.1.6`, actividad principal
`biz.siyi.pilot.app.HomeActivity`) — es decir, **es literalmente la misma
app "SIYI TX" que ya estaba instalada** en el mando (misma que se
actualizó de 1.1.210 a 3.1.6 en la sesión del 2026-09-09), no una app
"UniGCS" independiente. "UniGCS" es solo el nombre de producto interno
(`"appName":"UniGCS"` aparece en su propia telemetría). Esto simplificó
mucho el debug en vivo de la sección de abajo: no hace falta instalar
nada, la app a depurar ya está en el dispositivo.

Primera pasada (2026-09-09, ver memoria del proyecto) fue solo a nivel de
`strings` sobre los `.dex` — sin `jadx`/`apktool` instalados no daba para
más. Sesión posterior: se descargó `jadx 1.5.6` (zip de su release de
GitHub, sin necesitar root/sudo) y se descompiló
`docs/UniGCS_prod_mk15_32_release_3_1_6_*.apk` completo (`--no-res -j 4`,
~20500 ficheros `.java`, 438 errores no fatales — normal en una app de
este tamaño). Es una estación de tierra basada en MAVLink
(`com.divpundir.mavlink`) para volar un dron; el SDK de Datalink de SIYI
(nuestro protocolo) es solo un rincón (`t5.*`,
`biz.siyi.protocol.bu.manufacturer.siyi`,
`biz.siyi.core.rcu.controller.*`, `biz.siyi.pilot.rcuservice.binder.*`,
`biz.siyi.pilot.rcuservice.RemoteControlService`). Nombres de clase
ofuscados (R8) salvo donde el propio proyecto conservó un nombre manual
(`b5`, `SystemSettingViewModel`, `InterConnectionViewModel`,
`RemoteControlService`, `SerialPort`...) — esos fueron los anclas para
navegar.

Confirmado de nuevo: la tabla CRC16 (`crc16_tab[256]`) es byte a byte
idéntica a la del manual, y la ruta `/dev/ttyHS3` (vista en la pasada de
strings) es un tercer puerto usado solo en otro modelo SIYI
(`h0.T3() || h0.S3() ? "/dev/ttyHS3" : "/dev/ttyHS0"`,
`biz/siyi/pilot/bu/flight/flycontrol/model/o.java:140`) — irrelevante
para el MK15.

### Catálogo de `CMD_ID` que usa la app y que **no están en el manual**

Encontrados buscando asignaciones al campo `CMD_ID` dentro de la clase
que construye tramas del protocolo RCU (`t5.e`, la misma que usan los
comandos ya documentados — `dest=16`/RCU en todos). La mayoría están en
`biz/siyi/pilot/rcuservice/binder/b5.java`, la clase "binder" de la RCU
que implementa uno por uno todos los comandos del SDK que la app conoce
(documentados y no documentados).

**Ninguno de estos se ha probado contra hardware real** — son hallazgos
de código, no de tráfico capturado. Todos son de **escritura** salvo
`0x14`/`0x24` (vacíos, se piden solos en cada conexión) y `0x42` (ver
hallazgo clave arriba). Por el mismo motivo que `0x17`/`0x4A`/`0x4D` ya
documentados, **no se debería probar ninguno de escritura sin luz verde
explícita** — algunos (`0x36` auto-frequency, `0x50` modo inalámbrico,
`0x56` punto de frecuencia) tocan directamente el enlace RF con el
vehículo.

| `CMD_ID` | Método fuente (`b5.java` salvo que se indique) | Payload | Qué parece hacer |
|---|---|---|---|
| `0x01` | `h3.a.b(boolean)` | 1 byte (bool) | **Activar/desactivar streaming de canales** — ver PROTOCOL.md, es el disparador de `0x20/0x01` en `ttyHS1`, resuelto 2026-09-11 |
| `0x09` | `B(RCChannel, boolean)` | canal + flag | Sin contexto claro más allá del nombre de los tipos |
| `0x0E` | `m2(RCChannel, RCPhysicsEntity)` | canal + (tipo, id) | Mapeo canal→entidad física — parece una variante/precursora del `0x4A` ya documentado |
| `0x11` | `m(StrokeInfo)` | canal + 2 enteros | Calibración de recorrido ("stroke") de un canal físico (min/max) |
| `0x14` | `r7.b.P2()`, llamado desde `t5.k.v()` | vacío | Comando de lectura sin nombre — se pide automáticamente en cada conexión, antes que `0x48` |
| `0x1B` | `P1(boolean)` | 1 byte | Flag genérico, sin más contexto |
| `0x1D` | `p(OOCProtectInfo)` | canal + `ProtectType` + entero | Configuración de protección "Out Of Control" (failsafe) por canal |
| `0x24` | `t5.k.v()`, 3ª llamada | vacío | Comando de lectura sin nombre — se pide automáticamente en cada conexión, después de `0x48` |
| `0x32` | `t(RCButtonType, RCButtonMode)` | 2 bytes (ordinales) | Modo de un botón físico (`LT1-5`/`RT1-4` → `FLIGHT`/`LOCK`/`SWITCH`/`UNLOCK`) |
| `0x34` | `z1(boolean)` | 2 bytes | Flag genérico, sin más contexto |
| `0x36` | `E1(AutoFrequencyState)` | 1 byte (`OFF`/`ON`/`SEARCH_OPTIMAL`) | Modo de búsqueda/salto automático de frecuencia RF |
| `0x3C` | `a1(RCDialWheelAutoCenterInfo)` | tipo de dial + bool + entero | Auto-centrado de un dial/rueda física |
| `0x3E` | `h3.a.a(boolean)` | 1 byte (bool) | Toggle sin identificar — **es el comando que la primerísima captura de `ttyHS1` de este proyecto (2026-09-07/08) vio** (`type=0x0c, sub_id=0x3e`); no relacionado con canales, resuelto 2026-09-11 |
| `0x44` | `s1(RCPhysicsEntity)` | 2 bytes (tipo, id) | ⚠️ **Conflicto**: el manual documenta `0x44` como lectura "Image Transmission Link Status" (ya confirmado funcionando arriba); aquí se usa como escritura para mapear una entidad física. Mismo `CMD_ID`, semántica distinta — sin resolver cuál prevalece en este firmware, no probar sin más contexto |
| `0x46` | `i(ArrayList)` | array variable | Probablemente escritura en bloque de varios mapeos/calibraciones a la vez |
| `0x50` | `f2(WirelessMode)` | 1 byte (`MODE_5/8/15/24KM`) | Selección de modo de alcance/potencia del enlace inalámbrico |
| `0x54` | `H0(int)` | 1 byte | Parámetro numérico sin contexto claro |
| `0x56` | `r1(FrequencyPoint)` | 1 byte | Selección manual de canal/punto de frecuencia RF (o `AUTOMATIC`) |
| `0x58` | `M0(ReceiverChannel)` | 1 byte (`CHANNEL_1..5`) | Selección de canal de receptor (config. multi-receptor) |
| `0x5A` | `t1(RcOutputMode)` | 1 byte (`OFF`/`PPM`/`SBUS`) | Modo de salida física del RC |
| `0x5F` | `d2(PWMMapInfo)` | 2 bytes (canal PWM, canal RC) | Mapeo de un canal PWM físico a un canal RC |
| `0x64` | `A0(RCChannel)` | 1+ bytes | Sin contexto claro adicional |
| `0x70` | `h3.a.c(ImageTransFrequencyBand)` | 1 byte (ordinal) | Banda de frecuencia de la transmisión de imagen — **dest=20, no 16** (el único de esta tabla con destino distinto de RCU) |
| `0x82` | `e1(ExternalSdkConnectType)` | 1 byte (0-6) | Tipo de conexión del "SDK externo" — ver hallazgo clave abajo |
| `0x84` | `h3.a.e(int, boolean, boolean)` | 2 bytes | "setMultiAirUnitMode" (nombre de log conservado) — sin explorar más |

### Hallazgo clave: `0x82` "External SDK Connect Type" — descartado para MK15

`CMD_ID 0x82` (130) escribe un `ExternalSdkConnectType`:
`A_USB_CONNECT(0)`, `BLU_CONNECT(1)`, `MIC_USB_CONNECT(2)`,
`UART_CONNECT(3)`, `UDP_CONNECT(4)`, `ASSISTANT_UART(5)`, `CLOSE(6)` —
sonaba muy prometedor como "el interruptor que falta" para activar
`0x42`, más allá del toggle "Datalink → Connection → UART" que ya se usa.

Pero `SystemSettingViewModel.java:3348` (el único punto donde la app
aplica este comando, tras leerlo de un `ExternalSdkConnectConfig`)
**comprueba el `DeviceType` primero y sale sin hacer nada si no es
`UNIRC7` o `UNIRC10`**:
```java
if ((deviceType != DeviceType.UNIRC7 && deviceType != DeviceType.UNIRC10)
        || externalSdkConnectConfig == null) {
    return d0Var;   // no-op
}
```
Y `DeviceType.MK15` tiene valor `"68"` — **el mismo product code `0x68`
que ya confirmamos que devuelve nuestro propio `0x47`/`0x40` en el
hardware real de este proyecto**. Es decir: este comando/pantalla
concreta de "External SDK" **no aplica al MK15** en esta versión de la
app — no es el interruptor que falta, y no vale la pena perseguir esta
pista más (aunque el `CMD_ID` en sí podría seguir siendo válido para
otros mandos SIYI, `UNIRC7`/`UNIRC10`).

## Referencia: verificación de CRC de todos los ejemplos del manual

Antes de enviar nada al hardware real, se recalculó la CRC16 de los 22
ejemplos *send*/*response* del manual (comandos `0x40` a `0x4D`) contra
nuestro propio algoritmo. **Solo 2 de 22 no cuadran** — ambos son
respuestas ACK, nunca peticiones:

| Ejemplo | CRC impresa | CRC calculada | Estado |
|---|---|---|---|
| `0x42` response (2Hz) | `0x88ff` | `0x8be2` | ❌ errata |
| `0x47` response | `0x216d` | `0x616d` | ❌ errata |
| Los otros 20 ejemplos | — | — | ✅ todos correctos |

Mismo patrón ya documentado en `FirmwareVersion.java`/`ChannelData.java`:
los ejemplos de *petición* del manual son siempre fiables; alguna
respuesta de ejemplo tiene un byte mal transcrito en el PDF.
