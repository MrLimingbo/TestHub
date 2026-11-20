import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Runs against the libraries extracted from the final application image. */
public class NativeImageProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var status = Files.readString(Path.of("/proc/self/status"));
        check(status.lines().anyMatch(line -> line.matches("Uid:\\s+65532\\s+65532\\s+65532\\s+65532")), "nonroot uid");
        check(status.lines().anyMatch(line -> line.matches("CapEff:\\s+0+")), "no effective capabilities");
        check(status.lines().anyMatch(line -> line.matches("NoNewPrivs:\\s+1")), "no new privileges");
        check(!Files.isWritable(Path.of("/etc/fonts")), "font configuration must be read-only");
        check(!Files.isWritable(Path.of(System.getProperty("user.home"))), "user font configuration must be read-only");

        var dom = DocumentBuilderFactory.newInstance();
        var sax = SAXParserFactory.newInstance();
        check(dom.getClass().getName().startsWith("com.sun.org.apache.xerces.internal."), "JDK DOM parser");
        check(sax.getClass().getName().startsWith("com.sun.org.apache.xerces.internal."), "JDK SAX parser");
        System.out.println("DOM=" + dom.getClass().getName() + " SAX=" + sax.getClass().getName());

        var text = "中文需求 / TestHub & <xml>\n第二行";
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("兼容性");
            sheet.createRow(0).createCell(0).setCellValue(text);
            sheet.getRow(0).createCell(1).setCellValue(42.25);
            sheet.setColumnWidth(0, 30 * 256);
            book.write(output);
            bytes = output.toByteArray();
        }
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            check(text.equals(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()), "XLSX Unicode round trip");
            check(book.getSheetAt(0).getRow(0).getCell(1).getNumericCellValue() == 42.25, "XLSX numeric round trip");
        }
        check(!Files.readString(Path.of("/proc/self/maps")).contains("libexpat"), "XLSX must not load native Expat");
        System.out.println("XLSX round trip passed; native Expat not loaded by XLSX");

        // API response decoding uses Charset.forName with a UTF-8 fallback.
        for (var name : new String[]{"CP949", "MS949", "ISO-2022-CN-EXT", "IBM1390", "IBM1399"}) {
            var charset = Charset.isSupported(name) ? Charset.forName(name) : StandardCharsets.UTF_8;
            var module = charset.getClass().getModule().getName();
            check("java.base".equals(module) || "jdk.charsets".equals(module), "JDK charset implementation");
            check("TestHub".equals(new String("TestHub".getBytes(charset), charset)), "charset round trip " + name);
            check(new String(new byte[]{(byte) 0xa2, (byte) 0xe8}, charset).length() <= 8,
                    "bounded malformed charset decoding " + name);
            check(new String(new byte[]{0x0e}, charset).length() <= 8,
                    "bounded terminal charset decoding " + name);
            System.out.println("Charset " + name + " uses " + charset.name() + " from " + module);
        }

        var families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        check(families.length > 0, "font discovery");
        var image = new BufferedImage(300, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            check(graphics.getFontMetrics().stringWidth("TestHub 123") > 0, "font metrics");
            graphics.drawString("TestHub 123", 5, 25);
        } finally {
            graphics.dispose();
        }
        var maps = Files.readString(Path.of("/proc/self/maps"));
        check(maps.contains("libfreetype.so.6.20.6"), "AWT must map the verified Debian FreeType library");
        check(!maps.contains("libfreetype.so.6.20.2"), "old FreeType must not be mapped");
        // OpenJDK can dlclose fontconfig after discovery. The caller also checks
        // LD_DEBUG output so a library unloaded before this snapshot is covered.
        check(!maps.contains("libexpat.so.1.13.0"), "old Expat must not be loaded");
        for (var module : new String[]{"UHC.so", "ISO-2022-CN-EXT.so", "IBM1390.so", "IBM1399.so"}) {
            check(!maps.lines().anyMatch(line -> line.contains("/gconv/" + module)),
                    "Java decoding must not load affected native converter " + module);
        }
        System.out.println("Font discovery, metrics and rendering passed; runtime restrictions passed");
        System.out.println("AWT mapped libfreetype.so.6.20.6 from the verified package");
    }
}
