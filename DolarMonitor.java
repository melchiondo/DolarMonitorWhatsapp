import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alerta de variacion del dolar oficial (Argentina) - version Java.
 *
 * Pensado para ejecutarse UNA VEZ por corrida (por ejemplo, disparado cada
 * 5-10 minutos por GitHub Actions). En cada corrida:
 *
 *   1. Consulta la cotizacion oficial en https://dolarapi.com/v1/dolares/oficial
 *   2. La compara con el ultimo valor guardado en ultimo_valor.json
 *   3. Si la diferencia supera el umbral, manda un WhatsApp (via CallMeBot)
 *   4. Guarda el nuevo valor y un historial acotado (historial.json)
 *   5. Regenera docs/index.html: una paginita de estado para publicar gratis
 *      con GitHub Pages (Settings -> Pages -> Deploy from branch -> /docs)
 *
 * No usa librerias externas: solo java.net.http (incluido desde Java 11) y
 * un parseo de JSON con expresiones regulares, ya que la respuesta de la
 * API es un objeto plano y simple.
 *
 * Variables de entorno:
 *   CALLMEBOT_PHONE    tu numero con codigo de pais, sin '+' (ej: 5491122334455)
 *   CALLMEBOT_APIKEY   la api key que te da CallMeBot
 *   UMBRAL_PESOS       avisar si varia al menos $X (default 1)
 *   UMBRAL_PORCENTAJE  avisar si varia al menos X% (default 0 = desactivado)
 *   CAMPO              "venta" o "compra" (default venta)
 *
 * Compilar y correr:
 *   javac DolarMonitor.java
 *   java DolarMonitor
 */
public class DolarMonitor {

    private static final String API_URL = "https://dolarapi.com/v1/dolares/oficial";
    private static final Path ARCHIVO_ESTADO = Path.of("ultimo_valor.json");
    private static final Path ARCHIVO_HISTORIAL = Path.of("historial.json");
    private static final Path ARCHIVO_PAGINA = Path.of("docs/index.html");
    private static final int MAX_HISTORIAL = 30;
    private static final ZoneId ZONA_HORARIA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final double umbralPesos;
    private final double umbralPorcentaje;
    private final String campo;
    private final String callmebotPhone;
    private final String callmebotApikey;
    private final HttpClient http = HttpClient.newHttpClient();

    public DolarMonitor() {
        this.umbralPesos = Double.parseDouble(env("UMBRAL_PESOS", "1"));
        this.umbralPorcentaje = Double.parseDouble(env("UMBRAL_PORCENTAJE", "0"));
        this.campo = env("CAMPO", "venta");
        this.callmebotPhone = env("CALLMEBOT_PHONE", "");
        this.callmebotApikey = env("CALLMEBOT_APIKEY", "");
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
            json = obtenerCotizacionJson();
        } catch (Exception e) {
            System.out.println("[" + ahora() + "] Error consultando la API: " + e.getMessage());
            System.exit(1);
            return;
        }

        double actual = extraerCampoNumerico(json, campo);
        Double ultimo = cargarUltimoValor();

        System.out.printf("[%s] Valor actual (%s): $%,.2f | Ultimo guardado: %s%n",
                ahora(), campo, actual, ultimo == null ? "ninguno" : String.format("$%,.2f", ultimo));

        if (ultimo == null) {
            guardarUltimoValor(actual);
            registrarHistorial(actual, null, true);
            notificarWhatsapp(String.format(
                    "✅ Monitor de dolar oficial iniciado.%n%s: $%,.2f", capitalizar(campo), actual));
            regenerarPagina(actual);
            return;
        }

        if (superaUmbral(ultimo, actual)) {
            double dif = actual - ultimo;
            double pct = dif / ultimo * 100;
            String flecha = dif > 0 ? "🔺 SUBIO" : "🔻 BAJO";
            String texto = String.format(
                    "%s el dolar oficial (%s)%n$%,.2f -> $%,.2f%nVariacion: %+,.2f (%+.2f%%)",
                    flecha, campo, ultimo, actual, dif, pct);
            notificarWhatsapp(texto);
            guardarUltimoValor(actual);
            registrarHistorial(actual, dif, true);
            regenerarPagina(actual);
            System.out.println("[" + ahora() + "] Alerta enviada.");
        } else {
            // No se toca ningun archivo: asi el workflow no genera un commit por corrida.
            System.out.println("[" + ahora() + "] Sin cambios relevantes, no se avisa.");
        }
    }

    private String obtenerCotizacionJson() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(java.time.Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Extrae un campo numerico simple de un JSON plano, ej: "venta":1234.5 */
    private double extraerCampoNumerico(String json, String campo) {
        Pattern p = Pattern.compile("\"" + campo + "\"\\s*:\\s*([0-9.]+)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("No se encontro el campo \"" + campo + "\" en la respuesta: " + json);
        }
        return Double.parseDouble(m.group(1));
    }

    private Double cargarUltimoValor() {
        try {
            if (!Files.exists(ARCHIVO_ESTADO)) return null;
            String contenido = Files.readString(ARCHIVO_ESTADO, StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("\"valor\"\\s*:\\s*([0-9.]+)");
            Matcher m = p.matcher(contenido);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private void guardarUltimoValor(double valor) {
        try {
            String json = String.format(Locale.ROOT, "{\"valor\": %.2f, \"fecha\": \"%s\"}", valor, ahora());
            Files.writeString(ARCHIVO_ESTADO, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[" + ahora() + "] No pude guardar el estado: " + e.getMessage());
        }
    }

    // ---------- Historial (para la página de estado) ----------

    private record Entrada(String fecha, double valor, Double variacion, boolean notifico) {}

    private List<Entrada> cargarHistorial() {
        List<Entrada> lista = new ArrayList<>();
        try {
            if (!Files.exists(ARCHIVO_HISTORIAL)) return lista;
            String contenido = Files.readString(ARCHIVO_HISTORIAL, StandardCharsets.UTF_8);
            Pattern p = Pattern.compile(
                    "\\{\"fecha\":\"([^\"]*)\",\"valor\":([0-9.]+),\"variacion\":(null|[-0-9.]+),\"notifico\":(true|false)\\}");
            Matcher m = p.matcher(contenido);
            while (m.find()) {
                String fecha = m.group(1);
                double valor = Double.parseDouble(m.group(2));
                Double variacion = m.group(3).equals("null") ? null : Double.parseDouble(m.group(3));
                boolean notifico = Boolean.parseBoolean(m.group(4));
                lista.add(new Entrada(fecha, valor, variacion, notifico));
            }
        } catch (IOException ignored) {
            // Si el archivo esta corrupto o no existe, arrancamos con historial vacio.
        }
        return lista;
    }

    private void registrarHistorial(double valor, Double variacion, boolean notifico) {
        List<Entrada> historial = cargarHistorial();
        historial.add(new Entrada(ahora(), valor, variacion, notifico));

        // Nos quedamos solo con las últimas MAX_HISTORIAL entradas.
        int desde = Math.max(0, historial.size() - MAX_HISTORIAL);
        List<Entrada> recortado = historial.subList(desde, historial.size());

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < recortado.size(); i++) {
            Entrada e = recortado.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.ROOT,
                    "{\"fecha\":\"%s\",\"valor\":%.2f,\"variacion\":%s,\"notifico\":%s}",
                    e.fecha(), e.valor(),
                    e.variacion() == null ? "null" : String.format(Locale.ROOT, "%.2f", e.variacion()),
                    e.notifico()));
        }
        sb.append("]");

        try {
            Files.writeString(ARCHIVO_HISTORIAL, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[" + ahora() + "] No pude guardar el historial: " + e.getMessage());
        }
    }

    // ---------- Página de estado (GitHub Pages) ----------

    private void regenerarPagina(double valorActual) {
        List<Entrada> historial = cargarHistorial();
        List<Entrada> ordenDesc = new ArrayList<>(historial);
        java.util.Collections.reverse(ordenDesc);

        StringBuilder filas = new StringBuilder();
        if (ordenDesc.isEmpty()) {
            filas.append("<tr><td colspan=\"3\" class=\"vacio\">Todavía no se registró ningún cambio.</td></tr>");
        } else {
            for (Entrada e : ordenDesc) {
                String estado = e.notifico()
                        ? "<span class=\"aviso\">Avisó ✓</span>"
                        : "<span class=\"sin-cambios\">sin cambios</span>";
                filas.append(String.format(
                        "<tr><td>%s</td><td>$%,.2f</td><td>%s</td></tr>%n",
                        escaparHtml(e.fecha()), e.valor(), estado));
            }
        }

        String ultimaFecha = historial.isEmpty() ? "sin datos" : historial.get(historial.size() - 1).fecha();

        String html = String.format("""
                <!DOCTYPE html>
                <html lang="es">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Dólar oficial → WhatsApp</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { font-family: -apple-system, system-ui, sans-serif; max-width: 640px;
                         margin: 0 auto; padding: 48px 16px; }
                  header p { color: #888; margin: 0 0 4px; font-size: 14px; }
                  h1 { margin: 4px 0 8px; font-size: 24px; }
                  .subt { color: #888; font-size: 14px; margin-bottom: 24px; }
                  .card { border: 1px solid #8883; border-radius: 12px; padding: 20px 24px; margin-bottom: 24px; }
                  .valor { display: flex; justify-content: space-between; align-items: baseline; }
                  .valor .num { font-size: 32px; font-weight: 600; }
                  .fila { display: flex; justify-content: space-between; font-size: 14px; color: #888; margin-top: 8px; }
                  table { width: 100%%; border-collapse: collapse; font-size: 14px; }
                  th { text-align: left; color: #888; font-weight: 500; padding: 6px 4px; border-bottom: 1px solid #8883; }
                  td { padding: 8px 4px; border-bottom: 1px solid #8882; }
                  .aviso { color: #2a7; font-weight: 600; }
                  .sin-cambios { color: #888; }
                  .vacio { color: #888; text-align: center; padding: 16px; }
                  footer { margin-top: 24px; font-size: 12px; color: #888; }
                </style>
                </head>
                <body>
                  <header>
                    <p>Monitor automático</p>
                    <h1>Dólar oficial → WhatsApp</h1>
                    <p class="subt">Corre en GitHub Actions cada 10 minutos. Avisa por WhatsApp y actualiza esta página solo cuando el valor cambia.</p>
                  </header>

                  <div class="card">
                    <div class="valor">
                      <span>Último valor (%s)</span>
                      <span class="num">$%,.2f</span>
                    </div>
                    <div class="fila"><span>Último cambio</span><span>%s</span></div>
                    <div class="fila"><span>Umbral de aviso</span><span>$%.2f</span></div>
                  </div>

                  <h2 style="font-size:14px;color:#888;font-weight:500;">Últimos cambios</h2>
                  <table>
                    <thead><tr><th>Fecha</th><th>Valor</th><th>Estado</th></tr></thead>
                    <tbody>
                %s
                    </tbody>
                  </table>

                  <footer>Generado automáticamente por DolarMonitor.java en cada corrida de GitHub Actions.</footer>
                </body>
                </html>
                """, campo, valorActual, escaparHtml(ultimaFecha), umbralPesos, filas.toString());

        try {
            Files.createDirectories(ARCHIVO_PAGINA.getParent());
            Files.writeString(ARCHIVO_PAGINA, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[" + ahora() + "] No pude generar la página: " + e.getMessage());
        }
    }

    private static String escaparHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void notificarWhatsapp(String texto) {
        if (callmebotPhone.isEmpty() || callmebotApikey.isEmpty()) {
            System.out.println("[" + ahora() + "] CallMeBot no configurado, no se envia WhatsApp.");
            return;
        }
        String textoCodificado = URLEncoder.encode(texto, StandardCharsets.UTF_8);
        String url = "https://api.callmebot.com/whatsapp.php?phone=" + callmebotPhone
                + "&text=" + textoCodificado + "&apikey=" + callmebotApikey;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String cuerpo = response.body();
            String recorte = cuerpo.length() > 200 ? cuerpo.substring(0, 200) : cuerpo;
            System.out.println("[" + ahora() + "] Respuesta CallMeBot: " + response.statusCode() + " " + recorte);
        } catch (Exception e) {
            System.out.println("[" + ahora() + "] Error enviando WhatsApp: " + e.getMessage());
        }
    }

    private boolean superaUmbral(double anterior, double actual) {
        double dif = Math.abs(actual - anterior);
        if (dif == 0) return false;
        if (umbralPesos > 0 && dif >= umbralPesos) return true;
        if (umbralPorcentaje > 0 && (dif / anterior * 100) >= umbralPorcentaje) return true;
        return false;
    }

    private static String ahora() {
        return LocalDateTime.now(ZONA_HORARIA).format(FORMATO_FECHA);
    }

    private static String capitalizar(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return (v == null || v.isEmpty()) ? porDefecto : v;
    }
}
