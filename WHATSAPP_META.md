# Configurar WhatsApp con la API oficial de Meta

El monitor manda las alertas por la WhatsApp Cloud API de Meta. Es gratis
si se respetan dos reglas:

1. **No cargar ningún medio de pago en Meta.** Sin tarjeta, Meta no tiene cómo
   cobrar: si algún día se pasara del límite gratis, deja de entregar y listo.
2. **Escribirle al bot una vez por día** (o automatizarlo desde el celular).
   Meta solo entrega texto libre gratis dentro de las 24 h siguientes a un
   mensaje tuyo al bot. Con la ventana cerrada las alertas se pierden en
   silencio.

El monitor nunca manda plantillas (lo único que Meta cobra en este uso).

## 1. Crear la app (una sola vez, ~15 minutos)

1. Entrar a <https://developers.facebook.com/> con tu cuenta de Facebook y
   registrarte como desarrollador si te lo pide.
2. **My Apps → Create app**. Elegir el caso de uso **"Connect with customers
   through WhatsApp"**. Si te pide un portfolio comercial (business
   portfolio), crear uno con tu nombre.
3. En la app, ir a **WhatsApp → API Setup**. Meta te da un **número de prueba**
   gratuito como remitente ("From").
   - Anotar el **Phone number ID** que aparece debajo del número. Es el secret
     `WHATSAPP_PHONE_ID`.
4. En **To**, agregar tu número de celular y verificarlo con el código que te
   llega.
5. Tocar **Send message** para mandarte el `hello_world`. Te llega por
   WhatsApp desde el número de prueba. **Respondele cualquier cosa** ("hola"):
   eso abre la primera ventana de 24 h. Agendá ese número como "Monitor Dólar".

## 2. Token permanente

El token que muestra API Setup vence en horas. Para uno que no vence:

1. Ir a <https://business.facebook.com/settings> → **Users → System users →
   Add**. Nombre "monitor", rol **Admin**.
2. **Assign assets**: la app (control total) y la cuenta de WhatsApp (control
   total).
3. **Generate token**: elegir la app, vencimiento **Never**, y los permisos
   `whatsapp_business_messaging` y `whatsapp_business_management`.
4. Copiar el token. Es el secret `WHATSAPP_TOKEN`. No se vuelve a mostrar.

## 3. Cargar los secrets en GitHub

En el repo: **Settings → Secrets and variables → Actions → New repository
secret**:

| Secret | Valor |
|---|---|
| `WHATSAPP_TOKEN` | el token permanente |
| `WHATSAPP_PHONE_ID` | el Phone number ID |
| `WHATSAPP_TO` | tu número con código de país, sin `+` (ej. `5491112345678`) |

Apenas `WHATSAPP_TOKEN` tiene valor, el monitor deja de usar CallMeBot.

**Números argentinos:** si en el log de Actions aparece el error `131030`
("Recipient phone number not in allowed list"), probar `WHATSAPP_TO` sin el 9
(ej. `541112345678`). Es una rareza conocida de Meta con Argentina.

## 4. Mantener abierta la ventana de 24 h

Mandarle cualquier mensaje al número del bot una vez por día. Responder una
alerta también cuenta. Para no depender de acordarse, automatizarlo en el
celular a una hora fija (por ejemplo 9:30, antes de que abra el mercado).

## Cómo verificar que anda

En **Actions**, abrir una corrida y buscar `Respuesta WhatsApp (Meta): 200`.
Un 401 es el token; un 400 con `131030` es el número (ver arriba).
