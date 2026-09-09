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

## Ingeniería inversa adicional: APK de UniGCS

Se hizo una pasada de reconocimiento sobre
`docs/UniGCS_prod_mk15_32_release_3_1_6_*.apk` (la app de estación
terrestre completa, distinta de "SIYI TX"/`biz.siyi.remotecontrol`),
extrayendo cadenas de texto de sus `classes.dex`/`classes2.dex` (no hay
`jadx`/`apktool` instalados en este entorno, así que fue solo a nivel
de strings, no descompilación completa — se puede profundizar si hace
falta). Hallazgos:

- Confirma las rutas `/dev/ttyHS0` y `/dev/ttyHS1` ya conocidas, y
  **revela una tercera: `/dev/ttyHS3`** — no documentada en el manual,
  posiblemente correspondiente a una de las otras 3 interfaces de la
  sección 4.8.3 (candidato: el "puerto de actualización RC" virtual
  sobre USB). Clases relacionadas: `biz.siyi.port.SerialPort`,
  `SerialPortCommunication`.
- Claves de UI internas (`page_channel_data`, `page_system_setting`,
  `channel_reverse`, `CHANNEL_REVERSE_SET`) confirman que esta app
  implementa conceptualmente los mismos comandos que el manual
  documenta, pero los nombres de clase están ofuscados (ProGuard/R8:
  `biz.siyi.protocol.rtsp.a`, `.b`, `.c`...) así que no aportó
  constantes `CMD_ID` nuevas ni pistas directas sobre cómo activar la
  salida de canales de `0x42`.

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
