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
import java.time.format.DateTimeFormatter;
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
 *   4. Guarda el nuevo valor para la proxima corrida
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
            notificarWhatsapp(String.format(
                    "✅ Monitor de dolar oficial iniciado.%n%s: $%,.2f", capitalizar(campo), actual));
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
            System.out.println("[" + ahora() + "] Alerta enviada.");
        } else {
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
            String json = String.format("{\"valor\": %.2f, \"fecha\": \"%s\"}", valor, ahora());
            Files.writeString(ARCHIVO_ESTADO, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[" + ahora() + "] No pude guardar el estado: " + e.getMessage());
        }
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
        return LocalDateTime.now().format(FORMATO_FECHA);
    }

    private static String capitalizar(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String env(String nombre, String porDefecto) {
        String v = System.getenv(nombre);
        return (v == null || v.isEmpty()) ? porDefecto : v;
    }
}
