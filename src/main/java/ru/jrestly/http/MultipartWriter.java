package ru.jrestly.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal RFC 7578 multipart/form-data writer: dash-boundary framing,
 * Content-Disposition with an optional filename, an explicit part Content-Type,
 * CRLF line endings and the closing delimiter.
 */
public final class MultipartWriter {

    private MultipartWriter() {
    }

    public static byte[] write(String boundary, List<MultipartPart> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] dashBoundary = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);

        for (MultipartPart part : parts) {
            out.writeBytes(dashBoundary);
            out.writeBytes(crlf);

            StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"")
                    .append(part.name())
                    .append('"');
            if (part.filename() != null) {
                disposition.append("; filename=\"").append(part.filename()).append('"');
            }
            out.writeBytes(disposition.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(crlf);

            if (part.contentType() != null) {
                out.writeBytes(("Content-Type: " + part.contentType()).getBytes(StandardCharsets.UTF_8));
                out.writeBytes(crlf);
            }

            out.writeBytes(crlf);
            out.writeBytes(part.content());
            out.writeBytes(crlf);
        }

        out.writeBytes(dashBoundary);
        out.writeBytes("--".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(crlf);

        return out.toByteArray();
    }
}
