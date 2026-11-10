package id.mydev.peoplecore.document.parser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class DocumentParser {
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private DocumentParser() { }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.exit(3);
        }
        try {
            byte[] bytes = System.in.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES || !valid(bytes, args[0])) {
                System.exit(2);
            }
        } catch (IOException | IllegalArgumentException exception) {
            System.exit(2);
        }
    }

    private static boolean valid(byte[] bytes, String mediaType) throws IOException {
        return switch (mediaType) {
            case "application/pdf" -> pdf(bytes);
            case "image/jpeg" -> image(bytes, "JPEG");
            case "image/png" -> image(bytes, "PNG");
            default -> false;
        };
    }

    private static boolean pdf(byte[] bytes) throws IOException {
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(bytes);
             PDDocument document = new PDFParser(source).parse(false)) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1) {
                return false;
            }
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                document.getPage(index).getMediaBox();
            }
            return true;
        }
    }

    private static boolean image(byte[] bytes, String expectedType) throws IOException {
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                if (!expectedType.equalsIgnoreCase(reader.getFormatName())) {
                    return false;
                }
                AtomicBoolean warning = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, message) -> warning.set(true));
                reader.setInput(stream);
                BufferedImage decoded = reader.read(0);
                return decoded != null && decoded.getWidth() > 0 && decoded.getHeight() > 0 && !warning.get();
            } finally {
                reader.dispose();
            }
        }
    }
}
