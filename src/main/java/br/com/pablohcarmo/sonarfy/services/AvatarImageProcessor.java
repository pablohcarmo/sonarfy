package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.AvatarProperties;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;

public class AvatarImageProcessor {
    private final AvatarProperties limits;
    public AvatarImageProcessor(AvatarProperties limits) { this.limits = limits; }

    // Limita bytes e pixels antes da decodificação; a nova imagem descarta os metadados originais.
    public byte[] png(MultipartFile file, Integer cropX, Integer cropY, Integer cropSize) {
        return process(file, cropX, cropY, cropSize, limits.size(), limits.size(), true);
    }

    public byte[] wallpaper(MultipartFile file, int maxWidth, int maxHeight) {
        return process(file, null, null, null, maxWidth, maxHeight, false);
    }

    private byte[] process(MultipartFile file, Integer cropX, Integer cropY, Integer cropSize,
                           int maxWidth, int maxHeight, boolean square) {
        if (file.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Image file is empty.");
        if (file.getSize() > limits.maxBytes()) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the byte limit.");
        try (var source = file.getInputStream()) {
            byte[] bytes = source.readNBytes(limits.maxBytes() + 1);
            if (bytes.length > limits.maxBytes()) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the byte limit.");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use a JPEG or PNG image.");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG"))
                        throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use a JPEG or PNG image.");
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > limits.maxPixels())
                        throw error(HttpStatus.BAD_REQUEST, "Image exceeds the pixel limit.");
                    if (reader.getNumImages(true) != 1 || (format.equalsIgnoreCase("PNG") && animatedPng(bytes)))
                        throw error(HttpStatus.BAD_REQUEST, "Image must be static.");
                    BufferedImage image = reader.read(0);
                    int orientation = 1;
                    var exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes)).getFirstDirectoryOfType(ExifIFD0Directory.class);
                    if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION))
                        orientation = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                    if (orientation < 1 || orientation > 8) orientation = 1;
                    int orientedWidth = orientation >= 5 ? height : width, orientedHeight = orientation >= 5 ? width : height;
                    int outputWidth, outputHeight;
                    if (square) {
                        boolean custom = cropX != null || cropY != null || cropSize != null;
                        if (custom && (cropX == null || cropY == null || cropSize == null))
                            throw error(HttpStatus.BAD_REQUEST, "Provide all crop coordinates.");
                        int side = custom ? cropSize : Math.min(orientedWidth, orientedHeight);
                        int x = custom ? cropX : (orientedWidth - side) / 2, y = custom ? cropY : (orientedHeight - side) / 2;
                        if (side <= 0 || x < 0 || y < 0 || (long) x + side > orientedWidth || (long) y + side > orientedHeight)
                            throw error(HttpStatus.BAD_REQUEST, "Crop is outside the oriented image.");
                        // Coordenadas referem-se à imagem orientada; recorta sem alocar outra imagem completa.
                        var bounds = transform(orientation, width, height).createInverse()
                                .createTransformedShape(new java.awt.Rectangle(x, y, side, side)).getBounds();
                        image = image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
                        outputWidth = maxWidth; outputHeight = maxHeight;
                    } else {
                        double scale = Math.min(1d, Math.min((double) maxWidth / orientedWidth, (double) maxHeight / orientedHeight));
                        outputWidth = Math.max(1, (int) Math.floor(orientedWidth * scale));
                        outputHeight = Math.max(1, (int) Math.floor(orientedHeight * scale));
                    }
                    var result = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);
                    var graphics = result.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        int drawWidth = orientation >= 5 ? outputHeight : outputWidth;
                        int drawHeight = orientation >= 5 ? outputWidth : outputHeight;
                        graphics.transform(transform(orientation, drawWidth, drawHeight));
                        graphics.drawImage(image, 0, 0, drawWidth, drawHeight, null);
                    } finally { graphics.dispose(); }
                    var output = new ByteArrayOutputStream();
                    ImageIO.write(result, "PNG", output);
                    return output.toByteArray();
                } finally { reader.dispose(); }
            }
        } catch (ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw error(HttpStatus.BAD_REQUEST, "Invalid image."); }
    }

    private AffineTransform transform(int orientation, int width, int height) {
        double[][] matrices = {{1,0,0,1,0,0}, {-1,0,0,1,width,0}, {-1,0,0,-1,width,height},
                {1,0,0,-1,0,height}, {0,1,1,0,0,0}, {0,1,-1,0,height,0},
                {0,-1,-1,0,height,width}, {0,-1,1,0,0,width}};
        return new AffineTransform(matrices[orientation - 1]);
    }

    private boolean animatedPng(byte[] bytes) {
        for (int offset = 8; offset + 12 <= bytes.length;) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            if (length < 0 || (long) offset + length + 12 > bytes.length) return true;
            if (ByteBuffer.wrap(bytes, offset + 4, 4).getInt() == 0x6163544c) return true;
            offset += length + 12;
        }
        return false;
    }

    private ResponseStatusException error(HttpStatus status, String reason) { return new ResponseStatusException(status, reason); }
}
