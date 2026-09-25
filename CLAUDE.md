# Monitor de cotizaciones del dólar

Monitor personal que sigue las cotizaciones del dólar en Argentina, avisa por
WhatsApp cuando alguna se mueve, y publica un tablero que compara el rendimiento
de una posición en pesos (FCI money market) contra dolarizarse.

Corre en GitHub Actions y se publica con GitHub Pages en
https://melchiondo.github.io/DolarMonitorWhatsapp/

**Costo: cero.** Es un requisito del proyecto, no una casualidad. Cualquier
cambio que introduzca un servicio pago hay que consultarlo antes.

## Cómo está armado

- `DolarMonitor.java` — todo el programa, un solo archivo, **sin dependencias
  externas**. Usa `java.net.http` (incluido en el JDK) y parsea JSON con
  expresiones regulares. No agregar Maven, Gradle ni librerías: la simplicidad
  de compilar con un `javac` suelto es deliberada.
- `.github/workflows/monitor.yml` — el cron y el loop.
- `ultimo_valor.json` — valor de **referencia** de cada cotización.
- `historial.json` — últimos cambios detectados.
- `serie.json` — un punto por día, alimenta el gráfico.
- `docs/index.html` — la página, **generada por el Java en cada corrida**.
  No editarla a mano: se pisa sola.

## Decisiones que NO hay que deshacer

Cada una está acá porque costó un bug o una medición. Si hay que cambiarlas,
que sea a propósito.

### 1. El cron es de baja frecuencia con loop interno

GitHub **no cumple** los cron de alta frecuencia. Con `*/10` medimos 9 corridas
programadas en 11 horas (deberían haber sido 66): bajo carga los retrasa o los
saltea sin ningún error visible.

Por eso cada corrida se queda viva ~50 minutos chequeando internamente cada 5
(`ITERACIONES` × `INTERVALO_SEG`), y alcanza con que GitHub acierte un disparo
por hora. El `concurrency` evita que dos corridas se pisen al pushear.

**No volver a un cron `*/5` o `*/10` sin loop.** Parece más simple y es peor.

### 2. Todo lo que escribe JSON o SVG usa `Locale.ROOT`

El locale argentino formatea `1234.56` como `1234,56`. Eso rompe dos cosas
distintas, y las dos ya pasaron:

- **JSON**: `{"valor": 1234,56}` es inválido y `Double.parseDouble` falla al
  releerlo. El monitor se rompe solo a la siguiente corrida.
- **Coordenadas SVG**: `cy="150,6"` no se interpreta como número y el gráfico
  no renderiza.

Para eso está el helper `coord()`. Regla: **números que lee una máquina van con
`Locale.ROOT`; números que lee una persona van con `LOCALE_AR`.** Nunca mezclar
en el mismo `String.format`.

### 3. La referencia de cada cotización solo avanza cuando ESA cotización avisó

En `ultimo_valor.json` el campo `valor` es el último valor **notificado**, no el
valor vivo. Si la referencia avanzara en cada corrida, una suba de a $0,30 nunca
acumularía hasta el umbral de $1 y el monitor quedaría ciego a los movimientos
lentos.

La página muestra los valores vivos; el archivo guarda las referencias. Que
difieran es correcto.

### 4. El `git add` del workflow lleva `|| true`

Si la API de dolarapi falla, algún archivo puede no existir y `git add` tira
`pathspec did not match`, haciendo fallar el paso entero. El `2>/dev/null || true`
deja que la corrida siga y reintente en el próximo ciclo.

Lo mismo con el `git pull --rebase`, pero ahí `|| true` **no alcanza**: si el
rebase choca, el repo queda a mitad de rebase y todos los chequeos siguientes
de esa corrida (que dura ~50 min) fallan sin subir nada. Por eso, si el pull
falla, se hace `git rebase --abort` y se reintenta en el próximo ciclo. Con el
loop corriendo muchas horas por día mientras se pushean cambios de código desde
afuera, **no sacar ese `--abort`**.

### 5. Banda muerta de 0,25 puntos en el cruce de la carrera

La alerta de "se dio vuelta la carrera" solo dispara cuando la ventaja supera
±0,25 puntos porcentuales (`BANDA_CRUCE`). Sin eso, cada oscilación alrededor
del empate mandaría un WhatsApp.

### 6. Un solo WhatsApp por corrida

Si se mueven varias cotizaciones a la vez, van todas en un mensaje. No mandar
siete mensajes seguidos.

### 7. Las alertas son simétricas

`superaUmbral()` usa `Math.abs()`: una baja de $12 avisa igual que una suba de
$12. Solo cambia la flecha (▲ / ▼). No hace falta "agregar" alertas de bajada.

### 8. Las cotizaciones nuevas no avisan

La primera vez que se ve una cotización se guarda sin notificar, para que el
primer arranque no dispare una ráfaga de siete alertas.

## Configuración

Todo vive en el `env:` del workflow, **no hardcodeado en el Java** (el Java solo
tiene defaults que coinciden con los valores actuales):

| Variable | Qué es |
|---|---|
| `UMBRAL_PESOS` | Variación mínima en $ para avisar |
| `UMBRAL_PORCENTAJE` | Variación mínima en % (0 = desactivado) |
| `CAMPO` | `venta` o `compra` |
| `CASAS` | Cotizaciones a seguir (vacío = todas) |
| `TNA_PESOS` | TNA del FCI. **Actualizar cuando cambie la tasa** |
| `FECHA_REFERENCIA` | Fecha de entrada a la posición (dd/MM/yyyy) |
| `VALOR_REFERENCIA` | Cotización de ese día |
| `CASA_REFERENCIA` | Contra qué cotización se mide la carrera |

Si el dueño pasa a dolarizar vía MEP en vez de oficial, hay que cambiar
`CASA_REFERENCIA` a `bolsa` **y** ajustar `VALOR_REFERENCIA` al MEP de la fecha
de entrada. Medir contra una cotización que no se opera da una carrera que no
es la real.

## Alcance del tablero

El tablero es un **marcador, no un pronóstico**: dice quién viene ganando la
carrera hasta hoy, no anticipa saltos. No contempla costos de entrada y salida
ni situación impositiva. La página lleva ese aviso al pie y **no hay que
sacarlo**. Tampoco convertir el proyecto en algo que emita recomendaciones de
compra o venta.

## Cómo probar un cambio

Compilar siempre antes de commitear:

```bash
javac -d build DolarMonitor.java
```

Para probar la lógica sin pegarle a la API real, reemplazar temporalmente
`obtenerCotizacionesJson()` por una lectura de archivo y correr escenarios con
JSON de prueba. Vale la pena cubrir: cotización nueva, sin cambios, suba, baja,
varias a la vez, y acumulación de variaciones chicas bajo el umbral.

Si se toca la página o el gráfico, **abrirla en un navegador y mirarla**. El bug
de las comas decimales en el SVG compilaba perfecto y pasaba todos los chequeos
de sintaxis: solo se vio al renderizar.

## Nota sobre el trabajo en paralelo

Este proyecto se edita desde dos lugares: Claude Code en VSCode y una
conversación de Claude que escribe archivos directamente en el disco. **Leer el
estado actual del archivo antes de reescribirlo**, porque puede tener cambios
del otro lado que no conviene pisar.
