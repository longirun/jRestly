package ru.jrestly.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

public class IO {

    public static byte[] readBytes(InputStream is) throws IOException {
        int len;
        int size = 1024;
        byte[] buf;

        if (is instanceof ByteArrayInputStream) {
            size = is.available();
            buf = new byte[size];
        } else {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            buf = new byte[size];
            while ((len = is.read(buf, 0, size)) != -1)
                bos.write(buf, 0, len);
            buf = bos.toByteArray();
        }
        return buf;
    }

    public static String readString(InputStream is, Charset charset) throws IOException {
        return new String(readBytes(is), charset);
    }

    public static void saveToFile(byte[] bytes, String path) throws IOException {
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        } else if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        Files.write(Paths.get(path), bytes);
    }

    public static String readFromFile(String path) throws IOException {
        return Files.readString(Paths.get(path));
    }
}
