package comp3011;

/*
 * NEW FILE (Assignment 3)
 *
 * Author: Simon Tran a3157542
 *
 * Purpose: Frame processor that draws the subtitle being spoken at the frame's timestamp onto the frame
 * Runs as the LAST processor, so effects like jitter or black and white never distort the text.
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.Frame;

public class FrameSubtitler extends FrameProcessor {

	// At most 42 characters per line
	private static final int MAX_LINE = 42;

	// Set from other threads (subtitle-fetcher, JavaFX button) but read on a pipeline thread -> volatile
	private volatile SubtitleClient.Subtitles subtitles;
	private volatile boolean enabled;

	// Cache for the current subtitle. 1 pipeline thread -> no locking
	private String cachedText;
	private BufferedImage cachedImage;

	public FrameSubtitler(boolean enabled) {
		this.enabled = enabled;
	}

	public void setSubtitles(SubtitleClient.Subtitles subtitles) {
		this.subtitles = subtitles;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public void process(Frame frame, InfoFrame info) throws Exception {

		SubtitleClient.Subtitles current = this.subtitles;
		
		// If the user has switched off subtitles, or the subtitles haven't arrived yet
		if (!enabled || current == null) {
			return; 
		}

		// Get the text of the segment being spoken at this frame's timestamp
		String text = textAt(current, info.timestampUs() / 1_000_000.0);
		if (text == null) {
			return; 
		}

		// Only redraw the text image when the subtitle changes, not every frame
		if (!text.equals(cachedText)) {
			cachedText = text;
			cachedImage = renderText(text);
		}

		drawOnto(frame, cachedImage);
	}

	// Draw the text image onto the video frame, centered horizontally and near the bottom
	private void drawOnto(Frame frame, BufferedImage textImage) {
		// The frame's pixels are in a ByteBuffer, in BGR order
		ByteBuffer pixels = (ByteBuffer) frame.image[0];

		// The text is drawn onto the video frame, centered horizontally and near the bottom
		int width = videoInfo.imageWidth();
		int left = (width - textImage.getWidth()) / 2;
		int top = videoInfo.imageHeight() - textImage.getHeight() - videoInfo.imageHeight() / 12;

		// Draw the text image onto the video frame, blending with the video pixels based on the text's alpha channel
		for (int row = 0; row < textImage.getHeight(); row++) {
			for (int col = 0; col < textImage.getWidth(); col++) {

				// The text image's pixel at (col,row) is drawn onto the video frame at (left+col, top+row)
				int x = left + col;
				int y = top + row;
				
				int argb = textImage.getRGB(col, row);
				int alpha = argb >>> 24;

				// If the text pixel is mostly transparent, or the video pixel is outside the frame, skip it
				if (alpha < 128 || x < 0 || x >= width || y < 0) {
					continue; 
				}

				// Calculated based on the row, column, stride, and number of channels
				int base = y * videoInfo.imageStride() + x * videoInfo.imageChannels();

				// The text image's pixel is in ARGB order, but the video frame's pixel is in BGR order, so we need to swap the channels
				for (int c = 0; c < 3; c++) {
					pixels.put(base + c, (byte) (argb >> (8 * c)));
				}
			}
		}
	}

	// White bold sans-serif text with a black outline, on a transparent background
	private BufferedImage renderText(String text) {

		// Font size is proportional to the video height, but never smaller than 12px
		int fontSize = Math.max(12, videoInfo.imageHeight() / 18);
		int outline = Math.max(1, fontSize / 16); // outline thickness in pixels
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);

		// A subtitle can have 2 lines, split at "\n"
		String[] lines = text.split("\n");

		// Measure every line. The image is as wide as the longest line, and as tall as all the lines, plus the outline
		FontMetrics metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(font);
		
		// Longest line, and as tall as all the lines, plus the outline
		int widest = 0;
		for (String line : lines) {
			widest = Math.max(widest, metrics.stringWidth(line));
		}

		int lineHeight = metrics.getHeight();
		BufferedImage image = new BufferedImage(widest + 2 * outline, lines.length * lineHeight + 2 * outline, BufferedImage.TYPE_INT_ARGB);

		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(font);

		// Each line is centred, and placed one line height below the one before
		for (int i = 0; i < lines.length; i++) {

			// The x coordinate is the left edge of the text, so that the text is centred in the image
			int x = outline + (widest - metrics.stringWidth(lines[i])) / 2;
			int y = outline + i * lineHeight + metrics.getAscent(); 
			drawOutlined(g, lines[i], x, y, outline);
		}
	
		g.dispose();


		return image;
	}

	// Draw one line of white text with a black outline: black copies in a square around it, then white on top
	private static void drawOutlined(Graphics2D g, String line, int x, int y, int outline) {
		g.setColor(Color.BLACK);
		for (int dx = -outline; dx <= outline; dx++) {
			for (int dy = -outline; dy <= outline; dy++) {
				g.drawString(line, x + dx, y + dy);
			}
		}
		g.setColor(Color.WHITE);
		g.drawString(line, x, y);
	}


	// Split the text into lines, each at most MAX_LINE characters long, breaking at whitespace and punctuation
	static List<String> splitLines(String text) {
		List<String> lines = new ArrayList<>();
		String line = "";

		// Split the text into words, and add them to the current line until it would exceed MAX_LINE characters
		for (String word : text.trim().split("\\s+")) {
			// The word doesn't fit on this line -> finish the line, and start a new one with the word
			if (!line.isEmpty() && line.length() + 1 + word.length() > MAX_LINE) {
				lines.add(line);
				line = word;
			} 
			// The line is empty -> start it with the word
			else if (line.isEmpty()) {
				line = word;
			} 
			// The word fits on this line -> add it to the line with a space
			else {
				line = line + " " + word;
			}

			// If the line is already half full, and the word ends with punctuation, finish the line early to avoid a long pause before the next subtitle
			if (line.length() >= MAX_LINE / 2 && word.matches(".*[,.?!;:]")) {
				lines.add(line);
				line = "";
			}
		}

		// Add the last line if it's not empty
		if (!line.isEmpty()) {
			lines.add(line);
		}
		return lines;
	}

	// The text of the segment being spoken at this time, or null in a gap between segments
	private static String textAt(SubtitleClient.Subtitles subs, double seconds) {

		for (SubtitleClient.Segment segment : subs.segments()) {

			// If the current time is inside this segment, return its text
			if (seconds >= segment.start() && seconds < segment.end()) {
				return segment.text();
			}
		}
		return null;
	}
}
