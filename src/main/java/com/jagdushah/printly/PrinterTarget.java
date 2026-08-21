package com.jagdushah.printly;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * One networked label printer from the config file.
 *
 * @param name       the name the frontend prints to, e.g. "pack-1"
 * @param host       printer IP or hostname
 * @param port       raw socket port, virtually always 9100
 * @param charset    how text payloads are encoded on the wire; TSPL printers are codepage-specific
 * @param lineEnding "as-is" (default), "crlf", or "lf" — applied to text payloads only
 * @param note       free-text description shown in the tray and /printers
 */
public record PrinterTarget(String name, String host, int port, Charset charset, String lineEnding, String note) {

    public static PrinterTarget from(Map<String, Object> m) {
        String name = Json.str(m, "name", "").trim();
        String host = Json.str(m, "host", "").trim();
        if (name.isEmpty() || host.isEmpty()) {
            throw new IllegalArgumentException("each printer needs a \"name\" and a \"host\"");
        }
        int port = (int) Json.num(m, "port", 9100);
        String charsetName = Json.str(m, "charset", "UTF-8");
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (RuntimeException e) {
            Log.warn("printer '" + name + "': unknown charset '" + charsetName + "', falling back to UTF-8");
            charset = StandardCharsets.UTF_8;
        }
        String lineEnding = Json.str(m, "lineEnding", "as-is").toLowerCase(Locale.ROOT);
        if (!lineEnding.equals("as-is") && !lineEnding.equals("crlf") && !lineEnding.equals("lf")) {
            Log.warn("printer '" + name + "': unknown lineEnding '" + lineEnding + "', using as-is");
            lineEnding = "as-is";
        }
        return new PrinterTarget(name, host, port, charset, lineEnding, Json.str(m, "note", ""));
    }

    /**
     * Encode a text payload for this printer.
     *
     * <p>Line-ending rewriting is opt-in because TSPL payloads may embed binary image data
     * (BITMAP / PUTBMP), where blind CR/LF substitution corrupts the picture.
     */
    public byte[] encodeText(String data) {
        String s = data;
        if (lineEnding.equals("crlf")) {
            s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
        } else if (lineEnding.equals("lf")) {
            s = s.replace("\r\n", "\n").replace("\r", "\n");
        }
        return s.getBytes(charset);
    }

    public String address() {
        return host + ":" + port;
    }
}
