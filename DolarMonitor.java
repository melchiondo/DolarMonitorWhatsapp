import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitor de cotizaciones del dolar (Argentina) con alertas por WhatsApp y
 * tablero de "carry vs devaluacion".
 *
 * Hace dos cosas independientes en cada corrida:
 *
 *  A) AVISO POR MOVIMIENTO. Sigue todas las cotizaciones que publica
 *     https://dolarapi.com/v1/dolares (oficial, blue, bolsa, contado con
 *     liquidacion, mayorista, cripto y tarjeta) y avisa por WhatsApp cuando
 *     alguna se mueve mas que el umbral. Si se mueven varias a la vez manda
 *     un solo mensaje con todas juntas.
 *
 *  B) TABLERO DE LA CARRERA. Compara el rendimiento acumulado de una posicion
 *     en pesos (un FCI money market a TNA_PESOS, que capitaliza diario) contra
 *     la devaluacion acumulada de una cotizacion de referencia, ambos desde una
 *     fecha de entrada. Publica el "dolar de equilibrio": el valor al que
 *     ambas opciones empatan. Avisa cuando la carrera se da vuelta.
 *
 *     Esto es un MARCADOR, no un pronostico: dice quien viene ganando, no
 *     que va a pasar. Ver el README.
 *
 * El valor de referencia de cada cotizacion solo avanza cuando esa cotizacion
 * disparo una alerta, para que las subas chicas se acumulen hasta el umbral en
 * vez de resetearse en cada corrida.
 *
 * Archivos que mantiene:
 *   ultimo_valor.json  valores de referencia + estado de la carrera
 *   historial.json     ultimos cambios detectados
 *   serie.json         un punto por dia (carry y devaluacion acumulados)
 *   docs/index.html    la pagina publicada por GitHub Pages
 *
 * Solo escribe archivos cuando hay algo nuevo (un cambio, un cruce de la
 * carrera, o el primer punto del dia), asi no genera commits vacios.
 *
 * Sin librerias externas: java.net.http (incluido desde Java 11) y parseo de
 * JSON con expresiones regulares, porque las respuestas son objetos planos.
 *
 * Variables de entorno:
 *   CALLMEBOT_PHONE     tu numero con codigo de pais, sin '+'
 *   CALLMEBOT_APIKEY    la api key de CallMeBot
 *   UMBRAL_PESOS        avisar si una cotizacion varia al menos $X (default 1)
 *   UMBRAL_PORCENTAJE   avisar si varia al menos X% (default 0 = desactivado)
 *   CAMPO               "venta" o "compra" (default venta)
 *   CASAS               lista separada por comas, vacio = todas
 *   TNA_PESOS           TNA nominal anual de tu posicion en pesos (default 17.5)
 *   FECHA_REFERENCIA    dd/MM/yyyy en que entraste a la posicion
 *   VALOR_REFERENCIA    cotizacion de ese dia
 *   CASA_REFERENCIA     que cotizacion usar para la carrera (default oficial)
 *
 * Compilar y correr:
 *   javac -d build DolarMonitor.java
 *   java -cp build DolarMonitor
 */
public class DolarMonitor {

    private static final String API_URL = "https://dolarapi.com/v1/dolares";
    private static final ZoneId ZONA_HORARIA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Locale LOCALE_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter FORMATO_FECHA_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Path ARCHIVO_ESTADO = Path.of("ultimo_valor.json");
    private static final Path ARCHIVO_HISTORIAL = Path.of("historial.json");
    private static final Path ARCHIVO_SERIE = Path.of("serie.json");
    private static final Path ARCHIVO_PAGINA = Path.of("docs/index.html");

    private static final int MAX_HISTORIAL = 50;
    private static final int MAX_SERIE = 180;

    /** Margen para no disparar la alerta de cruce con cada oscilacion sobre cero. */
    private static final double BANDA_CRUCE = 0.0025;   // 0,25 puntos porcentuales

    private final double umbralPesos;
    private final double umbralPorcentaje;
    private final String campo;
    private final List<String> casasFiltro;
    private final String callmebotPhone;
    private final String callmebotApikey;

    private final double tnaPesos;
    private final LocalDate fechaReferencia;
    private final double valorReferencia;
    private final String casaReferencia;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Cotizacion(String casa, String nombre, double valor) {}

    private record Cambio(String casa, String nombre, double anterior, double actual) {
        double diferencia() { return actual - anterior; }
        double porcentaje() { return anterior == 0 ? 0 : (actual - anterior) / anterior * 100; }
    }

    private record Entrada(String fecha, String casa, String nombre, double valor, double variacion) {}

    /** Un punto diario de la carrera. carry y deval son fracciones (0,0106 = 1,06%). */
    private record Punto(String fecha, double valor, double carry, double deval) {}

    /** Estado calculado de la carrera en este instante. */
    private record Carrera(long dias, double carry, double deval, double equilibrio, double valorActual) {
        double ventaja() { return carry - deval; }
        boolean ganaPesos() { return ventaja() >= 0; }
        double brechaPesos() { return valorActual - equilibrio; }
    }

    public DolarMonitor() {
        this.umbralPesos = Double.parseDouble(env("UMBRAL_PESOS", "1"));
        this.umbralPorcentaje = Double.parseDouble(env("UMBRAL_PORCENTAJE", "0"));
        this.campo = env("CAMPO", "venta");
        this.callmebotPhone = env("CALLMEBOT_PHONE", "");
        this.callmebotApikey = env("CALLMEBOT_APIKEY", "");

        String casas = env("CASAS", "").trim();
        this.casasFiltro = casas.isEmpty()
                ? List.of()
                : List.of(casas.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));

        this.tnaPesos = Double.parseDouble(env("TNA_PESOS", "17.5")) / 100.0;
        this.valorReferencia = Double.parseDouble(env("VALOR_REFERENCIA", "1530"));
        this.casaReferencia = env("CASA_REFERENCIA", "oficial");
        this.fechaReferencia = LocalDate.parse(env("FECHA_REFERENCIA", "03/09/2026"), FORMATO_FECHA);
    }

    public static void main(String[] args) {
        new DolarMonitor().run();
    }

    private void run() {
        if (!campo.equals("venta") && !campo.equals("compra")) {
            System.err.println("CAMPO debe ser \"venta\" o \"compra\"");
            System.exit(1);
        }

        String json;
        try {
            json = obtenerCotizacionesJson();
        } catch (Exception e) {
            System.out.println("[" + ahora() + "] Error consultando la API: " + e.getMessage());
            System.exit(1);
            return;
        }

        List<Cotizacion> actuales = parsearCotizaciones(json);
        if (actuales.isEmpty()) {
            System.out.println("[" + ahora() + "] La API no devolvio cotizaciones utilizables.");
            System.exit(1);
            return;
        }

        Map<String, Double> referencias = cargarReferencias();
        String signoGuardado = cargarSignoCarrera();

        // ---- A) Cambios por movimiento ----
        List<Cambio> cambios = new ArrayList<>();
        boolean hayNuevas = false;
        for (Cotizacion c : actuales) {
            Double previo = referencias.get(c.casa());
            if (previo == null) {
                hayNuevas = true;
                System.out.printf("[%s] %s: $%s (nueva, no se avisa)%n",
                        ahora(), c.nombre(), montoAR(c.valor()));
            } else if (superaUmbral(previo, c.valor())) {
                cambios.add(new Cambio(c.casa(), c.nombre(), previo, c.valor()));
            }
        }

        // ---- B) Estado de la carrera ----
        Carrera carrera = calcularCarrera(actuales);
        String signoActual = signoCarrera(carrera, signoGuardado);
        boolean huboCruce = carrera != null
                && signoGuardado != null
                && !signoGuardado.equals(signoActual);

        // ---- Serie diaria ----
        List<Punto> serie = cargarSerie();
        boolean diaNuevo = carrera != null && agregarPuntoDelDia(serie, carrera);

        if (cambios.isEmpty() && !hayNuevas && !huboCruce && !diaNuevo) {
            System.out.printf("[%s] %d cotizaciones sin novedades, no se toca nada.%n",
                    ahora(), actuales.size());
            return;
        }

        // ---- Avisos ----
        List<String> mensajes = new ArrayList<>();
        if (!cambios.isEmpty()) {
            mensajes.add(mensajeCambios(cambios));
            for (Cambio c : cambios) {
                System.out.printf("[%s] %s: $%s -> $%s (%s%s)%n",
                        ahora(), c.nombre(), montoAR(c.anterior()), montoAR(c.actual()),
                        c.diferencia() >= 0 ? "+" : "", montoAR(c.diferencia()));
            }
            registrarHistorial(cambios);
        }
        if (huboCruce) {
            mensajes.add(mensajeCruce(carrera, signoActual));
            System.out.printf("[%s] LA CARRERA SE DIO VUELTA: ahora gana %s.%n",
                    ahora(), signoActual.equals("pesos") ? "el FCI en pesos" : "el dolar");
        }
        if (!mensajes.isEmpty()) {
            notificarWhatsapp(String.join("\n\n———\n\n", mensajes));
        }

        // ---- Persistencia ----
        Map<String, Double> nuevasReferencias = new LinkedHashMap<>(referencias);
        for (Cotizacion c : actuales) {
            nuevasReferencias.putIfAbsent(c.casa(), c.valor());
        }
        for (Cambio c : cambios) {
            nuevasReferencias.put(c.casa(), c.actual());
        }

        guardarEstado(actuales, nuevasReferencias, signoActual);
        if (diaNuevo) guardarSerie(serie);
        regenerarPagina(actuales, carrera, serie);
    }

    // ---------- Consulta a la API ----------

    private String obtenerCotizacionesJson() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private List<Cotizacion> parsearCotizaciones(String json) {
        List<Cotizacion> lista = new ArrayList<>();
        Matcher objetos = Pattern.compile("\\{[^{}]*\\}").matcher(json);
        while (objetos.find()) {
            String obj = objetos.group();
            String casa = extraerTexto(obj, "casa");
            String nombre = extraerTexto(obj, "nombre");
            Double valor = extraerNumero(obj, campo);
            if (casa == null || valor == null) continue;
            if (!casasFiltro.isEmpty() && !casasFiltro.contains(casa.toLowerCase(Locale.ROOT))) continue;
            lista.add(new Cotizacion(casa, nombre == null ? casa : nombre, valor));
        }
        return lista;
    }

    private static String extraerTexto(String json, String campo) {
        Matcher m = Pattern.compile("\"" + campo + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Double extraerNumero(String json, String campo) {
        Matcher m = Pattern.compile("\"" + campo + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    // ---------- La carrera ----------

    /** Tasa diaria equivalente del FCI (capitaliza todos los dias). */
    private double tasaDiaria() {
        return tnaPesos / 365.0;
    }

    private Carrera calcularCarrera(List<Cotizacion> actuales) {
        Cotizacion ref = actuales.stream()
                .filter(c -> c.casa().equalsIgnoreCase(casaReferencia))
                .findFirst().orElse(null);
        if (ref == null || valorReferencia <= 0) return null;

        long dias = ChronoUnit.DAYS.between(fechaReferencia, LocalDate.now(ZONA_HORARIA));
        if (dias < 0) dias = 0;

        double carry = Math.pow(1 + tasaDiaria(), dias) - 1;
        double deval = ref.valor() / valorReferencia - 1;
        double equilibrio = valorReferencia * Math.pow(1 + tasaDiaria(), dias);
        return new Carrera(dias, carry, deval, equilibrio, ref.valor());
    }

    /**
     * Devuelve "pesos" o "dolar". Mantiene el signo guardado mientras la ventaja
     * este dentro de la banda muerta, para no avisar por cada oscilacion.
     */
    private String signoCarrera(Carrera carrera, String signoGuardado) {
        if (carrera == null) return signoGuardado;
        if (carrera.ventaja() > BANDA_CRUCE) return "pesos";
        if (carrera.ventaja() < -BANDA_CRUCE) return "dolar";
        return signoGuardado != null ? signoGuardado : (carrera.ganaPesos() ? "pesos" : "dolar");
    }

    // ---------- Estado ----------

    private Map<String, Double> cargarReferencias() {
        Map<String, Double> estado = new LinkedHashMap<>();
        try {
            if (!Files.exists(ARCHIVO_ESTADO)) return estado;
            String contenido = Files.readString(ARCHIVO_ESTADO, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "\\{\"casa\":\"([^\"]*)\",\"nombre\":\"[^\"]*\",\"valor\":([0-9.]+)\\}")
                    .matcher(contenido);
            while (m.find()) estado.put(m.group(1), Double.parseDouble(m.group(2)));
        } catch (IOException ignored) {
        }
        return estado;
    }

    private String cargarSignoCarrera() {
        try {
            if (!Files.exists(ARCHIVO_ESTADO)) return null;
            String contenido = Files.readString(ARCHIVO_ESTADO, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"ganando\"\\s*:\\s*\"([^\"]*)\"").matcher(contenido);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void guardarEstado(List<Cotizacion> cotizaciones, Map<String, Double> referencia, String signo) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"actualizado\": \"").append(ahora()).append("\",\n");
        sb.append("  \"campo\": \"").append(campo).append("\",\n");
        sb.append("  \"ganando\": \"").append(signo == null ? "pesos" : signo).append("\",\n");
        sb.append("  \"_nota\": \"valor = ultima referencia avisada de cada cotizacion, ")
          .append("no necesariamente el valor vivo\",\n");
        sb.append("  \"cotizaciones\": [\n");
        for (int i = 0; i < cotizaciones.size(); i++) {
            Cotizacion c = cotizaciones.get(i);
            double valor = referencia.getOrDefault(c.casa(), c.valor());
            sb.append(String.format(Locale.ROOT,
                    "    {\"casa\":\"%s\",\"nombre\":\"%s\",\"valor\":%.2f}%s%n",
                    c.casa(), c.nombre(), valor, i < cotizaciones.size() - 1 ? "," : ""));
        }
        sb.append("  ]\n}");
        escribir(ARCHIVO_ESTADO, sb.toString(), "estado");
    }

    // ---------- Historial de cambios ----------

    private List<Entrada> cargarHistorial() {
        List<Entrada> lista = new ArrayList<>();
        try {
            if (!Files.exists(ARCHIVO_HISTORIAL)) return lista;
            String contenido = Files.readString(ARCHIVO_HISTORIAL, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "\\{\"fecha\":\"([^\"]*)\",\"casa\":\"([^\"]*)\",\"nombre\":\"([^\"]*)\","
                            + "\"valor\":([0-9.]+),\"variacion\":(-?[0-9.]+)\\}").matcher(contenido);
            while (m.find()) {
                lista.add(new Entrada(m.group(1), m.group(2), m.group(3),
                        Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5))));
            }
        } catch (IOException ignored) {
        }
        return lista;
    }

    private void registrarHistorial(List<Cambio> cambios) {
        List<Entrada> historial = cargarHistorial();
        String momento = ahora();
        for (Cambio c : cambios) {
            historial.add(new Entrada(momento, c.casa(), c.nombre(), c.actual(), c.diferencia()));
        }
        List<Entrada> recortado = ultimos(historial, MAX_HISTORIAL);

        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < recortado.size(); i++) {
            Entrada e = recortado.get(i);
            sb.append(String.format(Locale.ROOT,
                    "  {\"fecha\":\"%s\",\"casa\":\"%s\",\"nombre\":\"%s\",\"valor\":%.2f,\"variacion\":%.2f}%s%n",
                    e.fecha(), e.casa(), e.nombre(), e.valor(), e.variacion(),
                    i < recortado.size() - 1 ? "," : ""));
        }
        sb.append("]");
        escribir(ARCHIVO_HISTORIAL, sb.toString(), "historial");
    }

    // ---------- Serie diaria ----------

    private List<Punto> cargarSerie() {
        List<Punto> lista = new ArrayList<>();
        try {
            if (!Files.exists(ARCHIVO_SERIE)) return lista;
            String contenido = Files.readString(ARCHIVO_SERIE, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "\\{\"fecha\":\"([^\"]*)\",\"valor\":([0-9.]+),"
                            + "\"carry\":(-?[0-9.]+),\"deval\":(-?[0-9.]+)\\}").matcher(contenido);
            while (m.find()) {
                lista.add(new Punto(m.group(1), Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
            }
        } catch (IOException ignored) {
        }
        return lista;
    }

    /** Agrega el punto de hoy si todavia no esta. Devuelve true si lo agrego. */
    private boolean agregarPuntoDelDia(List<Punto> serie, Carrera carrera) {
        String hoy = LocalDate.now(ZONA_HORARIA).format(FORMATO_FECHA);
        for (Punto p : serie) {
            if (p.fecha().equals(hoy)) return false;
        }
        serie.add(new Punto(hoy, carrera.valorActual(), carrera.carry(), carrera.deval()));
        return true;
    }

    private void guardarSerie(List<Punto> serie) {
        List<Punto> recortado = ultimos(serie, MAX_SERIE);
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < recortado.size(); i++) {
            Punto p = recortado.get(i);
            sb.append(String.format(Locale.ROOT,
                    "  {\"fecha\":\"%s\",\"valor\":%.2f,\"carry\":%.6f,\"deval\":%.6f}%s%n",
                    p.fecha(), p.valor(), p.carry(), p.deval(),
                    i < recortado.size() - 1 ? "," : ""));
        }
        sb.append("]");
        escribir(ARCHIVO_SERIE, sb.toString(), "serie");
    }

    // ---------- WhatsApp ----------

    private String mensajeCambios(List<Cambio> cambios) {
        StringBuilder sb = new StringBuilder();
        sb.append(cambios.size() == 1
                ? "Se movio una cotizacion (" + campo + ")"
                : "Se movieron " + cambios.size() + " cotizaciones (" + campo + ")");
        for (Cambio c : cambios) {
            sb.append(String.format(LOCALE_AR,
                    "%n%n%s %s%n$%,.2f -> $%,.2f%n%s%,.2f (%s%.2f%%)",
                    c.diferencia() > 0 ? "\u25B2" : "\u25BC", c.nombre(),
                    c.anterior(), c.actual(),
                    c.diferencia() >= 0 ? "+" : "", c.diferencia(),
                    c.porcentaje() >= 0 ? "+" : "", c.porcentaje()));
        }
        return sb.toString();
    }

    private String mensajeCruce(Carrera c, String signo) {
        boolean pesos = "pesos".equals(signo);
        return String.format(LOCALE_AR,
                "SE DIO VUELTA LA CARRERA%n%nAhora conviene: %s%n%n"
                        + "Desde el %s (%d dias)%n"
                        + "Carry FCI: %+.2f%%%nDevaluacion: %+.2f%%%nVentaja: %+.2f pts%n%n"
                        + "Dolar de equilibrio: $%,.2f%nDolar hoy: $%,.2f",
                pesos ? "quedarse en pesos" : "estar dolarizado",
                fechaReferencia.format(FORMATO_FECHA), c.dias(),
                c.carry() * 100, c.deval() * 100, c.ventaja() * 100,
                c.equilibrio(), c.valorActual());
    }

    private void notificarWhatsapp(String texto) {
        if (callmebotPhone.isEmpty() || callmebotApikey.isEmpty()) {
            System.out.println("[" + ahora() + "] CallMeBot no configurado, no se envia WhatsApp.");
            return;
        }
        String url = "https://api.callmebot.com/whatsapp.php?phone=" + callmebotPhone
                + "&text=" + URLEncoder.encode(texto, StandardCharsets.UTF_8)
                + "&apikey=" + callmebotApikey;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            String cuerpo = r.body();
            System.out.println("[" + ahora() + "] Respuesta CallMeBot: " + r.statusCode()
                    + " " + (cuerpo.length() > 200 ? cuerpo.substring(0, 200) : cuerpo));
        } catch (Exception e) {
            System.out.println("[" + ahora() + "] Error enviando WhatsApp: " + e.getMessage());
        }
    }

    // ---------- Pagina ----------

    private void regenerarPagina(List<Cotizacion> actuales, Carrera carrera, List<Punto> serie) {
        String html = String.format(LOCALE_AR, """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Dólar: carry vs devaluación</title>
                <style>
                  :root {
                    color-scheme: light;
                    --surface: #fcfcfb; --plane: #f9f9f7;
                    --ink: #0b0b0b; --ink-2: #52514e; --muted: #898781;
                    --grid: #e1e0d9; --axis: #c3c2b7; --line: rgba(11,11,11,0.10);
                    --s1: #2a78d6; --s2: #eb6834;
                    --good: #0ca30c; --crit: #d03b3b;
                  }
                  @media (prefers-color-scheme: dark) {
                    :root:not([data-theme="light"]) {
                      color-scheme: dark;
                      --surface: #1a1a19; --plane: #0d0d0d;
                      --ink: #ffffff; --ink-2: #c3c2b7; --muted: #898781;
                      --grid: #2c2c2a; --axis: #383835; --line: rgba(255,255,255,0.10);
                      --s1: #3987e5; --s2: #d95926;
                      --good: #0ca30c; --crit: #d03b3b;
                    }
                  }
                  * { box-sizing: border-box; }
                  body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                         background: var(--plane); color: var(--ink);
                         max-width: 700px; margin: 0 auto; padding: 40px 16px 64px; }
                  .kicker { color: var(--muted); font-size: 13px; margin: 0 0 4px; }
                  h1 { margin: 0 0 8px; font-size: 22px; letter-spacing: -0.01em; }
                  .lede { color: var(--ink-2); font-size: 14px; line-height: 1.5; margin: 0 0 28px; }
                  .card { background: var(--surface); border: 1px solid var(--line);
                          border-radius: 14px; padding: 20px 22px; margin-bottom: 20px; }
                  h2 { font-size: 13px; color: var(--muted); font-weight: 600; margin: 0 0 14px;
                       text-transform: uppercase; letter-spacing: 0.04em; }
                  .hero { font-size: 40px; font-weight: 650; letter-spacing: -0.02em; margin: 0; }
                  .hero-sub { color: var(--ink-2); font-size: 14px; margin: 6px 0 0; line-height: 1.5; }
                  .pill { display: inline-block; font-size: 13px; font-weight: 600;
                          padding: 3px 10px; border-radius: 999px; margin-bottom: 12px; }
                  .pill.pesos { color: var(--good); border: 1px solid var(--good); }
                  .pill.dolar { color: var(--crit); border: 1px solid var(--crit); }
                  .metricas { display: flex; flex-wrap: wrap; gap: 24px; margin-top: 18px;
                              padding-top: 18px; border-top: 1px solid var(--line); }
                  .metrica .lbl { color: var(--muted); font-size: 12px; display: block; }
                  .metrica .val { font-size: 19px; font-weight: 600; font-variant-numeric: tabular-nums; }
                  .cotiz { display: flex; justify-content: space-between; align-items: baseline;
                           padding: 11px 0; border-bottom: 1px solid var(--line); }
                  .cotiz:last-child { border-bottom: none; }
                  .cotiz .nom { color: var(--ink-2); font-size: 14px; }
                  .cotiz .mto { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }
                  table { width: 100%%; border-collapse: collapse; font-size: 13px; }
                  th { text-align: left; color: var(--muted); font-weight: 500; padding: 6px 4px;
                       border-bottom: 1px solid var(--axis); }
                  td { padding: 9px 4px; border-bottom: 1px solid var(--line);
                       font-variant-numeric: tabular-nums; }
                  tr:last-child td { border-bottom: none; }
                  .sube { color: var(--good); font-weight: 600; }
                  .baja { color: var(--crit); font-weight: 600; }
                  .vacio { color: var(--muted); text-align: center; padding: 18px; }
                  .leyenda { display: flex; gap: 18px; font-size: 13px; color: var(--ink-2);
                             margin: 0 0 10px; }
                  .leyenda span { display: inline-flex; align-items: center; gap: 7px; }
                  .sw { width: 11px; height: 11px; border-radius: 3px; display: inline-block; }
                  svg { display: block; width: 100%%; height: auto; overflow: visible; }
                  button { font: inherit; font-size: 14px; font-weight: 600; cursor: pointer;
                           color: var(--surface); background: var(--ink); border: none;
                           border-radius: 9px; padding: 10px 18px; margin-top: 14px; }
                  button:hover { opacity: 0.85; }
                  button:focus-visible { outline: 2px solid var(--s1); outline-offset: 2px; }
                  #copiado { font-size: 13px; font-weight: 600; margin-left: 12px; }
                  #copiado.ok { color: var(--good); }
                  #copiado.error { color: var(--crit); }
                  footer { color: var(--muted); font-size: 12px; line-height: 1.6; margin-top: 8px; }
                  .aviso { color: var(--muted); font-size: 12px; line-height: 1.6;
                           border-left: 2px solid var(--axis); padding-left: 12px; margin-top: 24px; }
                </style>
                </head>
                <body>
                  <p class="kicker">Monitor automático</p>
                  <h1>Dólar: carry vs devaluación</h1>
                  <p class="lede">Compara tu posición en pesos (FCI money market, %s%% TNA) contra
                  quedarte en dólares, desde el %s. Avisa por WhatsApp cuando alguna cotización
                  se mueve o cuando la carrera se da vuelta.</p>

                %s
                %s
                  <div class="card">
                    <h2>Cotizaciones ahora</h2>
                %s  </div>

                  <div class="card">
                    <h2>Últimos movimientos</h2>
                    <table>
                      <thead><tr><th>Fecha</th><th>Cotización</th><th>Valor</th><th>Variación</th></tr></thead>
                      <tbody>
                %s      </tbody>
                    </table>
                  </div>

                %s
                  <p class="aviso"><strong>Esto es un marcador, no un pronóstico.</strong>
                  Dice quién viene ganando la carrera hasta hoy; no anticipa saltos. Tampoco
                  contempla el costo de entrada y salida (spread, comisiones) ni tu situación
                  impositiva. No es asesoramiento financiero.</p>

                  <footer>Actualizado: %s · Generado por DolarMonitor.java (campo: %s)</footer>
                </body>
                </html>
                """,
                numeroAR(tnaPesos * 100), fechaReferencia.format(FORMATO_FECHA),
                bloqueCarrera(carrera), bloqueGrafico(serie),
                bloqueCotizaciones(actuales), bloqueHistorial(),
                bloqueCopiar(actuales, carrera, serie), ahora(), campo);

        try {
            Files.createDirectories(ARCHIVO_PAGINA.getParent());
            Files.writeString(ARCHIVO_PAGINA, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[" + ahora() + "] No pude generar la página: " + e.getMessage());
        }
    }

    private String bloqueCarrera(Carrera c) {
        if (c == null) {
            return "  <div class=\"card\"><h2>La carrera</h2>"
                    + "<p class=\"hero-sub\">Todavía no hay datos de la cotización de referencia ("
                    + escaparHtml(casaReferencia) + ").</p></div>\n";
        }
        boolean pesos = c.ganaPesos();
        return String.format(LOCALE_AR, """
                  <div class="card">
                    <h2>La carrera</h2>
                    <span class="pill %s">%s</span>
                    <p class="hero">$%,.2f</p>
                    <p class="hero-sub">Dólar de equilibrio: por encima de ese valor, haberte
                    dolarizado el %s habría rendido más. Hoy el %s está en
                    <strong>$%,.2f</strong> (%s$%,.2f).</p>
                    <div class="metricas">
                      <div class="metrica"><span class="lbl">Carry del FCI</span>
                        <span class="val">%+.2f%%</span></div>
                      <div class="metrica"><span class="lbl">Devaluación</span>
                        <span class="val">%+.2f%%</span></div>
                      <div class="metrica"><span class="lbl">Ventaja</span>
                        <span class="val">%+.2f pts</span></div>
                      <div class="metrica"><span class="lbl">Días</span>
                        <span class="val">%d</span></div>
                    </div>
                  </div>
                """,
                pesos ? "pesos" : "dolar",
                pesos ? "Vienen ganando los pesos" : "Viene ganando el dólar",
                c.equilibrio(), fechaReferencia.format(FORMATO_FECHA),
                escaparHtml(casaReferencia), c.valorActual(),
                c.brechaPesos() >= 0 ? "+" : "", c.brechaPesos(),
                c.carry() * 100, c.deval() * 100, c.ventaja() * 100, c.dias());
    }

    /**
     * Grafico de dos lineas: carry acumulado vs devaluacion acumulada, ambos en
     * puntos porcentuales sobre el mismo eje (misma unidad, un solo eje).
     */
    private String bloqueGrafico(List<Punto> serie) {
        if (serie.size() < 2) {
            return "  <div class=\"card\"><h2>Evolución</h2>"
                    + "<p class=\"hero-sub\">El gráfico aparece cuando haya al menos dos días de datos.</p>"
                    + "</div>\n";
        }

        final int W = 640, H = 200, PL = 6, PR = 76, PT = 14, PB = 26;
        double max = 0, min = 0;
        for (Punto p : serie) {
            max = Math.max(max, Math.max(p.carry(), p.deval()));
            min = Math.min(min, Math.min(p.carry(), p.deval()));
        }
        double span = Math.max(max - min, 0.001);
        max += span * 0.12;
        min -= span * 0.12;
        span = max - min;

        int n = serie.size();
        double anchoUtil = W - PL - PR, altoUtil = H - PT - PB;

        StringBuilder carryPts = new StringBuilder();
        StringBuilder devalPts = new StringBuilder();
        StringBuilder puntos = new StringBuilder();

        for (int i = 0; i < n; i++) {
            Punto p = serie.get(i);
            double x = PL + (n == 1 ? anchoUtil / 2 : anchoUtil * i / (n - 1));
            double yc = PT + altoUtil * (max - p.carry()) / span;
            double yd = PT + altoUtil * (max - p.deval()) / span;
            carryPts.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, yc));
            devalPts.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, yd));

            // Zona de hover por punto: circulo transparente con <title> nativo.
            // OJO: las coordenadas van con punto decimal (coord), el texto visible
            // con formato argentino. Mezclar los dos rompe el SVG.
            puntos.append(String.format(LOCALE_AR,
                    "      <circle cx=\"%s\" cy=\"%s\" r=\"9\" fill=\"transparent\">"
                            + "<title>%s\nFCI %+.2f%% · Dólar %+.2f%% · $%,.2f</title></circle>%n",
                    coord(x), coord((yc + yd) / 2), escaparHtml(p.fecha()),
                    p.carry() * 100, p.deval() * 100, p.valor()));
        }

        double yCero = PT + altoUtil * (max - 0) / span;
        Punto ult = serie.get(n - 1);
        double xUlt = PL + anchoUtil;
        double ycUlt = PT + altoUtil * (max - ult.carry()) / span;
        double ydUlt = PT + altoUtil * (max - ult.deval()) / span;
        // Si las etiquetas se superponen, las separo un poco.
        if (Math.abs(ycUlt - ydUlt) < 14) {
            if (ycUlt <= ydUlt) { ycUlt -= 7; ydUlt += 7; } else { ycUlt += 7; ydUlt -= 7; }
        }

        return String.format(LOCALE_AR, """
                  <div class="card">
                    <h2>Evolución desde el %s</h2>
                    <p class="leyenda">
                      <span><i class="sw" style="background:var(--s1)"></i>FCI en pesos</span>
                      <span><i class="sw" style="background:var(--s2)"></i>Dólar %s</span>
                    </p>
                    <svg viewBox="0 0 %d %d" role="img"
                         aria-label="Rendimiento acumulado del FCI en pesos comparado con la devaluación, desde el %s">
                      <line x1="%d" y1="%s" x2="%s" y2="%s" stroke="var(--axis)" stroke-width="1"/>
                      <polyline points="%s" fill="none" stroke="var(--s1)" stroke-width="2"
                                stroke-linecap="round" stroke-linejoin="round"/>
                      <polyline points="%s" fill="none" stroke="var(--s2)" stroke-width="2"
                                stroke-linecap="round" stroke-linejoin="round"/>
                      <text x="%s" y="%s" fill="var(--s1)" font-size="12" font-weight="600"
                            dominant-baseline="middle">%+.2f%%</text>
                      <text x="%s" y="%s" fill="var(--s2)" font-size="12" font-weight="600"
                            dominant-baseline="middle">%+.2f%%</text>
                      <text x="%d" y="%d" fill="var(--muted)" font-size="11">%s</text>
                      <text x="%s" y="%d" fill="var(--muted)" font-size="11" text-anchor="end">%s</text>
                %s    </svg>
                  </div>
                """,
                fechaReferencia.format(FORMATO_FECHA), escaparHtml(casaReferencia),
                W, H, fechaReferencia.format(FORMATO_FECHA),
                PL, coord(yCero), coord(W - PR), coord(yCero),
                carryPts.toString().trim(), devalPts.toString().trim(),
                coord(xUlt + 8), coord(ycUlt), ult.carry() * 100,
                coord(xUlt + 8), coord(ydUlt), ult.deval() * 100,
                PL, H - 8, escaparHtml(serie.get(0).fecha()),
                coord(W - PR), H - 8, escaparHtml(ult.fecha()),
                puntos);
    }

    /**
     * Boton que copia al portapapeles un resumen completo del tablero, listo
     * para pegar en claude.ai y preguntar. El texto vive en un <pre> oculto,
     * asi no hay que escaparlo como literal de JavaScript.
     */
    private String bloqueCopiar(List<Cotizacion> actuales, Carrera carrera, List<Punto> serie) {
        return """
                  <div class="card">
                    <h2>Analizar con Claude</h2>
                    <p class="hero-sub" style="margin-top:0">Copia todo este tablero
                    (tu posición, la carrera, las cotizaciones y la serie histórica) para
                    pegarlo en una conversación y preguntar lo que quieras.</p>
                    <button id="btn-copiar" type="button">Copiar contexto</button>
                    <span id="copiado" role="status" aria-live="polite"></span>
                    <pre id="contexto" hidden>%s</pre>
                  </div>

                  <script>
                    (function () {
                      var boton = document.getElementById('btn-copiar');
                      var aviso = document.getElementById('copiado');
                      var texto = document.getElementById('contexto').textContent;

                      function confirmar(ok) {
                        aviso.textContent = ok ? 'Copiado \\u2713' : 'No se pudo copiar';
                        aviso.className = ok ? 'ok' : 'error';
                        setTimeout(function () { aviso.textContent = ''; }, 2500);
                      }

                      function copiarViejo() {
                        // Respaldo para navegadores sin Clipboard API o fuera de https.
                        var ta = document.createElement('textarea');
                        ta.value = texto;
                        ta.setAttribute('readonly', '');
                        ta.style.position = 'fixed';
                        ta.style.opacity = '0';
                        document.body.appendChild(ta);
                        ta.select();
                        var ok = false;
                        try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
                        document.body.removeChild(ta);
                        confirmar(ok);
                      }

                      // La Clipboard API a veces se queda colgada sin resolver ni
                      // rechazar (permisos, contextos raros). Sin este timeout el
                      // boton no daria ninguna respuesta.
                      function conLimite(promesa, ms) {
                        return new Promise(function (ok, falla) {
                          var listo = false;
                          var t = setTimeout(function () {
                            if (!listo) { listo = true; falla(new Error('timeout')); }
                          }, ms);
                          promesa.then(
                            function (v) { if (!listo) { listo = true; clearTimeout(t); ok(v); } },
                            function (e) { if (!listo) { listo = true; clearTimeout(t); falla(e); } }
                          );
                        });
                      }

                      boton.addEventListener('click', function () {
                        if (navigator.clipboard && window.isSecureContext) {
                          conLimite(navigator.clipboard.writeText(texto), 1200)
                            .then(function () { confirmar(true); })
                            .catch(copiarViejo);
                        } else {
                          copiarViejo();
                        }
                      });
                    })();
                  </script>
                """.formatted(escaparHtml(contextoTexto(actuales, carrera, serie)));
    }

    /** El resumen en texto plano que se copia al portapapeles. */
    private String contextoTexto(List<Cotizacion> actuales, Carrera carrera, List<Punto> serie) {
        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXTO DEL MONITOR DE DÓLAR — snapshot del ").append(ahora())
          .append(" (hora de Buenos Aires)\n\n");

        sb.append("MI POSICIÓN\n");
        sb.append(String.format(LOCALE_AR,
                "- Instrumento: FCI money market en pesos, TNA declarada %s%%%n"
                        + "- Rendimiento efectivo: %.2f%% anual, %.3f%% mensual, %.4f%% diario "
                        + "(capitaliza todos los días)%n"
                        + "- Entré el %s, con el dólar %s (%s) a $%,.2f%n",
                numeroAR(tnaPesos * 100),
                (Math.pow(1 + tasaDiaria(), 365) - 1) * 100,
                (Math.pow(1 + tasaDiaria(), 30) - 1) * 100,
                tasaDiaria() * 100,
                fechaReferencia.format(FORMATO_FECHA), casaReferencia, campo, valorReferencia));

        if (carrera != null) {
            sb.append(String.format(LOCALE_AR,
                    "%nLA CARRERA (carry en pesos vs devaluación), %d días corridos%n"
                            + "- Carry acumulado del FCI: %+.3f%%%n"
                            + "- Devaluación acumulada del %s: %+.3f%%%n"
                            + "- Ventaja de haberme quedado en pesos: %+.3f puntos porcentuales%n"
                            + "- Dólar de equilibrio hoy: $%,.2f (por encima de ese valor, "
                            + "dolarizarme el %s habría rendido más)%n"
                            + "- Dólar %s hoy: $%,.2f (%s$%,.2f respecto del equilibrio)%n"
                            + "- Viene ganando: %s%n",
                    carrera.dias(), carrera.carry() * 100, casaReferencia, carrera.deval() * 100,
                    carrera.ventaja() * 100, carrera.equilibrio(),
                    fechaReferencia.format(FORMATO_FECHA),
                    casaReferencia, carrera.valorActual(),
                    carrera.brechaPesos() >= 0 ? "+" : "", carrera.brechaPesos(),
                    carrera.ganaPesos() ? "el FCI en pesos" : "el dólar"));
        }

        sb.append(String.format("%nCOTIZACIONES AHORA (campo: %s)%n", campo));
        for (Cotizacion c : actuales) {
            sb.append(String.format(LOCALE_AR, "- %s: $%,.2f%n", c.nombre(), c.valor()));
        }

        if (!serie.isEmpty()) {
            sb.append("\nSERIE DIARIA (fecha | dólar | carry acumulado | devaluación acumulada)\n");
            for (Punto p : serie) {
                sb.append(String.format(LOCALE_AR, "%s | %,.2f | %+.3f%% | %+.3f%%%n",
                        p.fecha(), p.valor(), p.carry() * 100, p.deval() * 100));
            }
        }

        List<Entrada> historial = cargarHistorial();
        if (!historial.isEmpty()) {
            sb.append("\nÚLTIMOS MOVIMIENTOS DETECTADOS\n");
            List<Entrada> desc = new ArrayList<>(historial);
            Collections.reverse(desc);
            for (Entrada e : desc) {
                sb.append(String.format(LOCALE_AR, "%s | %s | $%,.2f | %s%,.2f%n",
                        e.fecha(), e.nombre(), e.valor(),
                        e.variacion() >= 0 ? "+" : "", e.variacion()));
            }
        }

        sb.append("""

                MI OBJETIVO
                Quiero decidir cuándo salir de la posición en pesos para dolarizarme, sin
                perder renta. Los datos de arriba son el marcador de cómo viene esa carrera
                hasta hoy.

                QUÉ NECESITO DE VOS
                - Buscá en la web el contexto macro argentino actual: régimen cambiario
                  vigente, tasa de política monetaria, brecha, y lo que haya pasado esta
                  semana. Los datos de arriba no lo incluyen.
                - Analizá qué me dicen estos números y qué NO me dicen.
                - Marcame los riesgos y lo que no estoy considerando.
                - Si algo no se puede saber, decímelo en vez de estimarlo con seguridad.

                No busco que me digas "comprá" o "vendé": busco entender bien el cuadro para
                decidir yo. Tené en cuenta que este marcador no contempla costos de entrada
                y salida ni mi situación impositiva.
                """);

        return sb.toString();
    }

    private String bloqueCotizaciones(List<Cotizacion> actuales) {
        StringBuilder sb = new StringBuilder();
        for (Cotizacion c : actuales) {
            sb.append(String.format(LOCALE_AR,
                    "    <div class=\"cotiz\"><span class=\"nom\">%s</span>"
                            + "<span class=\"mto\">$%,.2f</span></div>%n",
                    escaparHtml(c.nombre()), c.valor()));
        }
        return sb.toString();
    }

    private String bloqueHistorial() {
        List<Entrada> historial = cargarHistorial();
        if (historial.isEmpty()) {
            return "        <tr><td colspan=\"4\" class=\"vacio\">Todavía no se registró"
                    + " ningún movimiento.</td></tr>\n";
        }
        List<Entrada> desc = new ArrayList<>(historial);
        Collections.reverse(desc);

        StringBuilder sb = new StringBuilder();
        for (Entrada e : desc) {
            sb.append(String.format(LOCALE_AR,
                    "        <tr><td>%s</td><td>%s</td><td>$%,.2f</td>"
                            + "<td class=\"%s\">%s%,.2f</td></tr>%n",
                    escaparHtml(e.fecha()), escaparHtml(e.nombre()), e.valor(),
                    e.variacion() >= 0 ? "sube" : "baja",
                    e.variacion() >= 0 ? "+" : "", e.variacion()));
        }
        return sb.toString();
    }

    // ---------- Utilidades ----------

    private boolean superaUmbral(double anterior, double actual) {
        double dif = Math.abs(actual - anterior);
        if (dif == 0) return false;
        if (umbralPesos > 0 && dif >= umbralPesos) return true;
        if (umbralPorcentaje > 0 && anterior != 0 && (dif / anterior * 100) >= umbralPorcentaje) return true;
        return false;
    }

    private static <T> List<T> ultimos(List<T> lista, int n) {
        return lista.subList(Math.max(0, lista.size() - n), lista.size());
    }

    private void escribir(Path destino, String contenido, String que) {
        try {
            Files.writeString(destino, contenido, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[" + ahora() + "] No pude guardar " + que + ": " + e.getMessage());
        }
    }

    /**
     * Coordenada para SVG. SIEMPRE con punto decimal: el locale argentino
     * escribiria "158,5" y el navegador no lo interpreta como numero.
     */
    private static String coord(double valor) {
        return String.format(Locale.ROOT, "%.1f", valor);
    }

    private static String montoAR(double valor) {
        return String.format(LOCALE_AR, "%,.2f", valor);
    }

    private static String numeroAR(double valor) {
        return String.format(LOCALE_AR, "%,.2f", valor).replaceAll(",00$", "");
    }

    private static String escaparHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String ahora() {
        return LocalDateTime.now(ZONA_HORARIA).format(FORMATO_FECHA_HORA);
    }

    private static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return (v == null || v.isEmpty()) ? porDefecto : v;
    }
}
