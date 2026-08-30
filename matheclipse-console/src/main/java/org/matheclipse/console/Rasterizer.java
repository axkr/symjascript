package org.matheclipse.console;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.DocumentLimits;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;

/**
 * Turns the SVG the engine produces into a raster image for <code>-format PNG</code> and
 * friends.
 *
 * <p>
 * Every AWT and jsvg reference in the console is confined to this class, and it is only
 * touched when a raster format is actually asked for. That matters because a native image on
 * macOS has no AWT at all - <code>BufferedImage</code>'s class initializer calls
 * <code>System.loadLibrary("awt")</code> and fails (oracle/graal#13272). Keeping the
 * references here means the failure surfaces as a catchable {@link NoClassDefFoundError} or
 * {@link UnsatisfiedLinkError} at the call site rather than killing the process, and the same
 * binary keeps working on Linux and Windows, where native-image does support AWT.
 */
final class Rasterizer {

  /**
   * jsvg's cap on the number of elements in a document. Matches the value matheclipse-image
   * uses, so a graphic that rasterises there rasterises here.
   */
  private static final int MAX_ELEMENTS = 100000;

  private Rasterizer() {}

  /**
   * @param svg the SVG document
   * @param format an ImageIO format name, upper case
   * @param out where the encoded image is written
   * @return false when ImageIO has no writer for the format
   */
  static boolean write(String svg, String format, OutputStream out) throws IOException {
    BufferedImage image = rasterize(svg);
    if (image == null) {
      return false;
    }
    String name = format.toLowerCase(java.util.Locale.US);
    // Encode into memory first: a writer that refuses the image reports it by returning
    // false, and buffering keeps a half-written image out of the caller's stream.
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    if (!ImageIO.write(image, name, buffer)) {
      // BMP and JPEG have no alpha channel and reject TYPE_INT_ARGB. Flatten onto white
      // and try once more.
      buffer.reset();
      if (!ImageIO.write(flatten(image), name, buffer)) {
        return false;
      }
    }
    buffer.writeTo(out);
    return true;
  }

  /** Composite onto an opaque white background, for formats that cannot store alpha. */
  private static BufferedImage flatten(BufferedImage source) {
    BufferedImage opaque =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g2d = opaque.createGraphics();
    try {
      g2d.setColor(java.awt.Color.WHITE);
      g2d.fillRect(0, 0, source.getWidth(), source.getHeight());
      g2d.drawImage(source, 0, 0, null);
    } finally {
      g2d.dispose();
    }
    return opaque;
  }

  private static BufferedImage rasterize(String svg) {
    SVGLoader loader = new SVGLoader();
    DocumentLimits limits = new DocumentLimits(DocumentLimits.DEFAULT_MAX_NESTING_DEPTH,
        DocumentLimits.DEFAULT_MAX_USE_NESTING_DEPTH, MAX_ELEMENTS);
    LoaderContext context = LoaderContext.builder().documentLimits(limits).build();
    SVGDocument doc = loader.load(
        new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), null, context);
    if (doc == null) {
      return null;
    }
    FloatSize size = doc.size();
    int width = (int) Math.ceil(size.width);
    int height = (int) Math.ceil(size.height);
    if (width <= 0) {
      width = 500;
    }
    if (height <= 0) {
      height = 500;
    }
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = image.createGraphics();
    try {
      g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      doc.render(null, g2d);
    } finally {
      g2d.dispose();
    }
    return image;
  }
}
