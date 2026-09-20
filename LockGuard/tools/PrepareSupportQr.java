import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Re-encodes an owner's payment QR without the surrounding name or avatar.
 * Usage: java ... PrepareSupportQr wechat|alipay source-image output.png
 * Requires ZXing core and javase 3.5.3. Does not contact a payment server.
 */
public class PrepareSupportQr {
    static String decode(Path path) throws Exception {
        var hints = new EnumMap<DecodeHintType,Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
            new BufferedImageLuminanceSource(ImageIO.read(path.toFile())))), hints).getText();
    }
    public static void main(String[] args) throws Exception {
        String payload = decode(Path.of(args[1]));
        boolean valid = switch (args[0]) {
            case "wechat" -> payload.startsWith("wxp://");
            case "alipay" -> payload.startsWith("https://qr.alipay.com/");
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Unexpected payment provider; refusing to generate");
        var hints = Map.of(EncodeHintType.MARGIN, 4, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 720, 720, hints);
        Path output = Path.of(args[2]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        MatrixToImageWriter.writeToPath(matrix, "PNG", output);
        if (!payload.equals(decode(output))) throw new IllegalStateException("Payment payload mismatch");
        System.out.println("PASS: original and generated payment destinations match exactly.");
    }
}
