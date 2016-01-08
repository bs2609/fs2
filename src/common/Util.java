package common;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;

import common.HttpUtil.SimpleDownloadProgress;

/**
 * All kinds of useful stuff that Java should just have already.
 * @author Gary
 */
public abstract class Util {

	/**
	 * Describes an object that can filter items.
	 * @param <T> The type of object that can be filtered by this.
	 * @author Gary
	 */
	public interface Filter<T> {
		
		/**
		 * Returns true if the item supplied passes through the filter.
		 * @param item
		 */
		boolean accept(T item);
	}
	
	/**
	 * Filters the collection supplied using a filter.
	 * @param collection The collection that will be filtered. It must support a modifiable iterator.
	 * @param filter The filter to use.
	 */
	public static <T> void filterList(Collection<T> collection, Filter<T> filter) throws UnsupportedOperationException {
		Iterator<T> it = collection.iterator();
		while (it.hasNext()) {
			if (!filter.accept(it.next())) it.remove();
		}
	}
	
	/** Wraps a Lock so it can be released automatically by a try-with block. */
	public static class LockHolder implements AutoCloseable {
		
		private final Lock lock;
		
		private LockHolder(Lock lock) {
			this.lock = lock;
			lock.lock();
		}
		
		public static LockHolder hold(Lock lock) {
			return new LockHolder(lock);
		}

		@Override
		public void close() {
			lock.unlock();
		}
	}
	
	/**
	 * Allows an iterator to pretend to be an enumeration.
	 * @author Gary
	 */
	public static class EnumerationWrapper<T> implements Enumeration<T> {
		
		private final Iterator<T> internal;
		
		public EnumerationWrapper(Iterable<T> input) {
			internal = input.iterator();
		}
		
		@Override
		public boolean hasMoreElements() {
			return internal.hasNext();
		}
		
		@Override
		public T nextElement() {
			return internal.next();
		}
	}
	
	/**
	 * Joins the string representation of objects together with a delimiter between them.
	 * @param items The array of objects to join.
	 * @param delim The separator/delimiter.
	 * @return
	 */
	public static String join(Object[] items, String delim) {
		StringJoiner sj = new StringJoiner(delim);
		for (Object item : items) {
			sj.add(String.valueOf(item));
		}
		return sj.toString();
	}
	
	/** Joins the string representation of objects together with a delimiter between them. */
	public static String join(Iterable<?> items, String delim) {
		StringJoiner sj = new StringJoiner(delim);
		for (Object item : items) {
			sj.add(String.valueOf(item));
		}
		return sj.toString();
	}
	
	/**
	 * Given a simple quoted string removes the quotes from the beginning and end, and unescapes the contents according to standard conventions:
	 * Examples:<br>
	 * "w00t\"" -> w00t"
	 * "w0\\\"t" -> w0\"t
	 * "\\" -> \
	 * @param toUnquote
	 * @return
	 */
	public static String unquote(String quoted) {
		if (quoted.length() < 2) return "";
		return quoted.substring(1, quoted.length() - 1).replaceAll("\\\\\"", Matcher.quoteReplacement("\"")).replaceAll("\\\\\\\\", Matcher.quoteReplacement("\\"));
	}
	
	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	public static String formatDate(Date date) {
		synchronized (dateFormat) {
			return dateFormat.format(date);
		}
	}
	
	public static String formatDate(Date date, String format) {
		return new SimpleDateFormat(format).format(date);
	}
	
	public static String formatCurrentDate() {
		return formatDate(new Date());
	}
	
	public static String formatCurrentDate(String format) {
		return formatDate(new Date(), format);
	}
	
	/**
	 * Wraps a byte array, in order to provide type-specific overrides of Object methods.
	 * Generally, is to byte[] as Byte is to byte, though more limited. (No boxing!)
	 * Delegates to java.util.Arrays for method implementations.
	 */
	public static class ByteArray implements Serializable {
		
		private static final long serialVersionUID = -6280850528719232401L;
		
		private static final ByteArray empty = new ByteArray(new byte[0]);
		
		private final byte[] array;
		
		public ByteArray(byte[] array) {
			this.array = array;
		}
		
		public byte[] get() {
			return array;
		}
		
		public static ByteArray empty() {
			return empty;
		}
		
		@Override
		public boolean equals(Object obj) {
			return obj == this || obj instanceof ByteArray && Arrays.equals(array, ((ByteArray) obj).array);
		}
		
		@Override
		public int hashCode() {
			return Arrays.hashCode(array);
		}
		
		@Override
		public String toString() {
			return Arrays.toString(array);
		}
	}
	
	public static String bytesToHexString(byte[] bytes) {
		CharBuffer cb = CharBuffer.allocate(bytes.length * 2);
		
		String hexChars = "0123456789abcdef";
		for (byte b : bytes) {
			cb.put(hexChars.charAt((b & 0xF0) >> 4));
			cb.put(hexChars.charAt(b & 0x0F));
		}
		
		return cb.flip().toString();
	}
	
	public static byte[] bytesFromHexString(String str) {
		if ((str.length() & 1) != 0) return bytesFromHexString("0" + str);
		
		ByteBuffer buf = ByteBuffer.allocate(str.length() / 2);
		
		for (int i = 0; i < str.length(); i += 2) {
			int b = asHex(str.charAt(i)) << 4;
			b |= asHex(str.charAt(i+1));
			buf.put((byte) b);
		}
		
		return buf.array();
	}
	
	public static int asHex(char c) {
		switch (c) {
			case '0': return 0x0;
			case '1': return 0x1;
			case '2': return 0x2;
			case '3': return 0x3;
			case '4': return 0x4;
			case '5': return 0x5;
			case '6': return 0x6;
			case '7': return 0x7;
			case '8': return 0x8;
			case '9': return 0x9;
			case 'a': return 0xa;
			case 'A': return 0xA;
			case 'b': return 0xb;
			case 'B': return 0xB;
			case 'c': return 0xc;
			case 'C': return 0xC;
			case 'd': return 0xd;
			case 'D': return 0xD;
			case 'e': return 0xe;
			case 'E': return 0xE;
			case 'f': return 0xf;
			case 'F': return 0xF;
		}
		throw new IllegalArgumentException("Invalid hex digit");
	}
	
	public static void copyFile(File src, File dst) throws IOException {
		InputStream is = new BufferedInputStream(Files.newInputStream(src.toPath()));
		writeStreamToFile(is, dst);
	}
	
	public static void writeStringToFile(String str, File file) throws IOException {
		InputStream is = new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8));
		writeStreamToFile(is, file);
	}
	
	/**
	 * Writes the given input stream to a file, then closes the file and stream.
	 * Uses a temporary file to avoid trashing an existing file if interrupted part-way through.
	 * 
	 * Always closes both the file and the stream.
	 * 
	 * @param stream
	 * @param file
	 * @throws IOException 
	 */
	public static void writeStreamToFile(InputStream stream, File file) throws IOException {
		writeStreamToFile(stream, file, null);
	}
	
	public static void writeStreamToFile(InputStream stream, File file, SimpleDownloadProgress progress) throws IOException {
		Path path = file.toPath();
		Path working = getWorkingCopy(path);
		
		try (InputStream is = stream; OutputStream os = new BufferedOutputStream(Files.newOutputStream(working))) {
			int bytesRead = 0;
			long soFar = 0;
			byte[] buf = new byte[FS2Constants.ARBITRARY_BUFFER_SIZE];
			
			while ((bytesRead = is.read(buf)) > -1) {
				soFar += bytesRead;
				os.write(buf, 0, bytesRead);
				if (progress != null) progress.progress(soFar);
			}
		}
		
		Files.deleteIfExists(path);
		Files.move(working, path);
	}
	
	public static void writeObjectToFile(Serializable object, File file) throws IOException {
		Path path = file.toPath();
		Path working = getWorkingCopy(path);
		
		try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(working)))) {
			oos.writeObject(object);
		}
		
		Files.deleteIfExists(path);
		Files.move(working, path);
	}
	
	/** Returns a new location that can be written to without affecting the original. */
	public static Path getWorkingCopy(Path path) {
		return path.resolveSibling(path.getFileName() + ".working");
	}
	
	public static final String[] sizeSuffix = {" B"," KiB"," MiB"," GiB"," TiB"," PiB"," EiB"};
	
	/**
	 * Returns a string describing the given size in bytes in nice human units.
	 * One decimal place.
	 * 
	 * @param inSize The long representing the number of bytes.
	 * @return
	 */
	public static String niceSize(long size) {
		return niceSize(size, false);
	}
	
	public static String niceSize(long size, boolean concise) {
		double working = size;
		int order = 0;
		// Keep increasing the order until we run out of known orders of bytes.
		while (working > 1024 && order < sizeSuffix.length - 1) {
			working /= 1024;
			order++;
		}
		return (order > 0 ? oneDecimalPlace(working) : (int) working) + (concise ? sizeOrders.substring(order, order + 1).toUpperCase() : sizeSuffix[order]);
	}
	
	public static final Pattern sizeTokeniser = Pattern.compile("(\\d+\\.?\\d*)\\s*([bkmgtpe]?)i?[ob]?", Pattern.CASE_INSENSITIVE);
	public static final String sizeOrders = "bkmgtpe";
	
	/**
	 * Parses an expression of a filesize and returns a long representing the decimal value.
	 * Examples: 100kb, 50mo, 1b, 888, 10m, 5.5tb etc.
	 * 
	 * @return The size represented by nizeSize or -1 on failure.
	 */
	public static long parseNiceSize(String niceSize) {
		Matcher m = sizeTokeniser.matcher(niceSize);
		if (!m.matches()) return -1;
		
		double sofar = Double.parseDouble(m.group(1));
		int order = sizeOrders.indexOf(m.group(2).toLowerCase());
		while (order > 0) {
			order--;
			sofar *= 1024;
		}
		return (long) sofar;
	}
	
	public static String oneDecimalPlace(double val) {
		return Double.toString(Math.round(val * 10.0) / 10.0);
	}
	
	public static class FileSize implements Comparable<FileSize> {
		
		private final long size;
		private final boolean speed;
		
		public long getSize() {
			return size;
		}

		public FileSize(long size) {
			this.size = size;
			this.speed = false;
		}
		
		/**
		 * Set speed to true and the toString() of this class will append "/s".
		 * @param size
		 * @param speed
		 */
		public FileSize(long size, boolean speed) {
			this.size = size;
			this.speed = speed;
		}
		
		@Override
		public int compareTo(FileSize o) {
			return Long.compare(size, o.size);
		}
		
		@Override
		public String toString() {
			return niceSize(size) + (speed ? "/s" : "");
		}
	}
	
	/**
	 * A class for printing magnitudes with SI-prefixed suffixes.
	 * This importantly differs from NiceSize in that it uses SI orders of magnitude and does not say "b" for x10^0.
	 * 
	 * @author gp
	 */
	public static class NiceMagnitude implements Comparable<NiceMagnitude> {

		private final long magnitude;
		private final String suffix;
		
		public NiceMagnitude(long magnitude, String suffix) {
			this.magnitude = magnitude;
			this.suffix = suffix;
		}
		
		@Override
		public String toString() {
			double working = magnitude;
			int order = 0;
			// Keep increasing the order until we run out of known orders.
			while (working > 1000 && order < sizeSuffix.length - 1) {
				working /= 1000;
				order++;
			}
			return (order > 0 ? oneDecimalPlace(working) : (int) working) + (order > 0 ? ("_KMGTPE").substring(order, order + 1) : "") + suffix;
		}
		
		@Override
		public int compareTo(NiceMagnitude o) {
			return Long.compare(magnitude, o.magnitude);
		}
	}
	
	private static final Map<Deferrable, Long> executeNeverFasterTable = new WeakHashMap<Deferrable, Long>();
	
	/**
	 * Given a Runnable this will execute it now if it hasn't been executed by this method in the last minInterval milliseconds.
	 * The interval is measured between the end of one 
	 * 
	 * Obviously this method keeps track of all previous tasks invoked using it, but it uses weak references so that it does not cause a memory leak.
	 * 
	 * Also note that this is not a scheduler, if this function returns false the Runnable is neither executed now nor asynchronously in the future.
	 * 
	 * @param minInterval The minimum duration in milliseconds between invocations of the task supplied.
	 * @param task The Runnable to execute not more often than specified. This MUST be the same object (by .equals), or it will always be executed.
	 * @return true iff the task was executed now, false if the task was not executed.
	 */
	public static boolean executeNeverFasterThan(long minInterval, Deferrable task) {
		Long lastRun;
		synchronized (executeNeverFasterTable) {
			lastRun = executeNeverFasterTable.get(task);
		}
		if (lastRun != null && lastRun + minInterval > System.currentTimeMillis()) {
			return false;
		}
		synchronized (executeNeverFasterTable) {
			executeNeverFasterTable.put(task, System.currentTimeMillis());
		}
		task.run();
		return true;
	}
	
	private static final Timer scheduleTimer = new Timer("Util scheduleExecuteNeverFasterThan timer", true);
	private static final Set<Deferrable> scheduleItems = new HashSet<Deferrable>();
	
	/**
	 * Similar to executeNeverFasterThan but differs:
	 * 1) They are executed never when the method is called, but only ever after minInterval
	 * 1b) they are executed in another thread.
	 * 2) A task when submitted will certainly execute while the program is active:
	 * 2a) although not more often than minInterval,
	 * 2b) and is not guaranteed to execute if the program shuts down before the interval has elapsed.
	 * 
	 * Also all scheduled tasks are executed in the same (timer) thread, so they shouldn't take too long!
	 * @param minInterval
	 * @param task
	 */
	public static void scheduleExecuteNeverFasterThan(long minInterval, final Deferrable task) {
		synchronized (scheduleItems) {
			if (scheduleItems.contains(task)) return;
			scheduleItems.add(task);
		}
		scheduleTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				task.run();
				synchronized (scheduleItems) {
					scheduleItems.remove(task);
				}
			}
		}, minInterval);
	}
	
	/**
	 * Used to represent tasks that can be deferred but does not imply they will be executed in another thread.
	 * @author Gary
	 */
	public interface Deferrable extends Runnable {
		
		void run();
	}
	
	private static final String[] timeOrders = { "s", "m", "h", "d", "w", "y", "c" };
	private static final int[] orderValues = { 60, 60, 24, 7, 52, 100 };
	private static final int[] remainderRounds = { 5, 5, 1, 1, 1, 1 };
	
	/**
	 * Describes the interval supplied in simple English.
	 * @param seconds The interval in seconds.
	 * @return
	 */
	public static String describeInterval(long seconds) {
		long tr = seconds;
		if (tr == Long.MAX_VALUE) return "forever";
		
		StringBuilder sb = new StringBuilder();
		int remainder = 0;
		int order = 0;
		
		while (order + 1 < orderValues.length && tr > orderValues[order]) {
			remainder = (int) (tr % orderValues[order]);
			tr /= orderValues[order];
			order++;
		}
		
		sb.append(tr).append(timeOrders[order]);
		
		int roundRem;
		// woof:
		if (order > 0 && (roundRem = roundTo(remainder, remainderRounds[order-1], true)) > 0) {
			sb.append(" ").append(roundRem).append(timeOrders[order-1]);
		}
		
		return sb.toString();
	}
	
	public static int roundTo(int value, int roundTo, boolean trunc) {
		return (int) ((value + (trunc ? 0.0 : 0.5) * roundTo) / roundTo) * roundTo;
	}
	
	public static long roundTo(long value, long roundTo, boolean trunc) {
		return (long) ((value + (trunc ? 0.0 : 0.5) * roundTo) / roundTo) * roundTo;
	}
	
	/**
	 * Checks that the file specified is contained by the container specified.
	 * --that all of the container's path is the leftmost section of the item's path.
	 * So, a file contains itself.
	 * and /home/gary/Desktop/test.jpg isWithin: /, /home, /home/gary, /home/gary/Desktop, and /home/gary/Desktop/test.jpg
	 */
	public static boolean isWithin(File item, File container) throws IOException {
		return item.toPath().toRealPath().startsWith(container.toPath().toRealPath());
	}
	
	/**
	 * Tests to determine if the file object given represents a valid filename for a potential file on this computer.
	 * It requires that the filesystem is read-write.
	 * @return
	 */
	public static boolean isValidFileName(File toTest) {
		Path path = toTest.toPath();
		if (Files.exists(path)) return true;
		try {
			return Files.deleteIfExists(Files.createFile(path));
			
		} catch (IOException e) {
			Logger.warn("Failed to test " + toTest + " for validity: " + e);
			Logger.log(e);
			return false;
		}
	}
	
	/**
	 * Returns the next line from the InputStream. This is all the bytes up until and including the next LF byte (0x0A or '\n').
	 * @param is An InputStream to read from. This is read byte-at-a-time so this should certainly be a buffered implementation!
	 * @return The line, as a byte array.
	 * @throws IOException if the underlying InputStream does.
	 */
	public static byte[] readLine(InputStream is) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		
		int next;
		do {
			next = is.read();
			if (next < 0) break;
			bos.write(next);
			
		} while (next != '\n');
		
		return bos.toByteArray();
	}
	
	/**
	 * Enables the TLS_DH_anon_WITH_AES_128_CBC_SHA cipher suite to be used in HttpsURLConnections.
	 * This is necessary because FS2 currently only uses non-authenticated crypto.
	 * 
	 * This is because the only reasonable way to do a decently secure, authenticated, standard crypto is with a PKI.
	 * Future FS2 protocols may use a PKI or clever authentication scheme to prevent MITM attacks...
	 * 
	 * @throws NoSuchAlgorithmException If crypto is unsupported on this system.
	 * @throws KeyManagementException 
	 */
	public static void enableDHanonCipherSuite() throws NoSuchAlgorithmException, KeyManagementException {
		SSLContext dsslc = SSLContext.getInstance("TLS");
		dsslc.init(null, null, null);
		String[] defaults = dsslc.getServerSocketFactory().getDefaultCipherSuites();
		String[] plusone = Arrays.copyOf(defaults, defaults.length + 1);
		plusone[defaults.length] = FS2Constants.DH_ANON_CIPHER_SUITE_USED;
		System.setProperty("https.cipherSuites", join(plusone, ","));
	}
	
	/**
	 * Calculates the md5 of the string in the default encoding, returning the result as a hex string.
	 * @param input
	 * @return
	 */
	public static String md5(String input) {
		return hash(input, "MD5");
	}
	
	public static String hash(String input, String algorithm) {
		try {
			return bytesToHexString(MessageDigest.getInstance(algorithm).digest(input.getBytes()));
			
		} catch (NoSuchAlgorithmException e) {
			Logger.severe("Platform does not support " + algorithm + ": " + e);
			Logger.log(e);
			return null;
		}
	}
	
	/**
	 * INNER never crops the original but centres the original inside bounds of the out-dimensions, 
	 * OUTER will crop and will always fill all pixels of the output buffer, 
	 * NORATIO will just resize to the new dimensions without ratio preservation 
	 * and NONE will not resize.
	 */
	public enum ImageResizeType {INNER, OUTER, NORATIO, NONE};
	
	/**
	 * Encodes a supplied image (in an InputStream) into a specified format and size.
	 * It can resize the image if the source is not equal to the destination.
	 * @param inImage The input stream containing any image format supported by default in Java.
	 * @param outFormat The format to serialise the image into. Can be any of: BMP,bmp,WBMP,wbmp,JPG,jpg,JPEG,jpeg,PNG,png,GIF,gif
	 * @param outWidth The intended height of the output.
	 * @param outHeight The intended height of the output.
	 * @param resizeMode The mode of resizing. See ImageResizeType for details.
	 * @return A buffer containing the new encoded image.
	 * @throws IOException 
	 */
	public static byte[] processImage(InputStream inImage, String outFormat, int outWidth, int outHeight, ImageResizeType resizeMode) throws IOException {
		BufferedImage im = processImageInternal(inImage, outWidth, outHeight, resizeMode);
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		ImageIO.write(im, outFormat, os);
		return os.toByteArray();
	}
	
	public static BufferedImage processImageInternal(InputStream inImage, int outWidth, int outHeight, ImageResizeType resizeMode) throws IOException {
		return resizeImage(ImageIO.read(inImage), outWidth, outHeight, resizeMode);
	}
	
	public static BufferedImage resizeImage(BufferedImage img, int outWidth, int outHeight, ImageResizeType resizeMode) {
		
		int inWidth = img.getWidth(), inHeight = img.getHeight();
		
		if (resizeMode == ImageResizeType.NONE || inWidth == outWidth && inHeight == outHeight) {
			return img;
		}
		
		// Image needs resizing:
		double widthRatio = (double) outWidth / (double) inWidth;
		double heightRatio = (double) outHeight / (double) inHeight;
		double chosenRatio = 1.0;
		
		if (resizeMode == ImageResizeType.INNER) {
			chosenRatio = Math.min(widthRatio, heightRatio);
			
		} else if (resizeMode == ImageResizeType.OUTER) {
			chosenRatio = Math.max(widthRatio, heightRatio);
		}
		
		// Modified (adjusted) output width and height.
		int mWidth = outWidth;
		int mHeight = outHeight;
		
		if (resizeMode != ImageResizeType.NORATIO) {
			mWidth = (int) ((double) inWidth * chosenRatio);
			mHeight = (int) ((double) inHeight * chosenRatio);
		}
		
		int oX = (outWidth - mWidth) / 2; // Offset from edge in X
		int oY = (outHeight - mHeight) / 2; // Offset from edge in Y
		
		BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = out.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); // Good quality interpolation.
		
		graphics.setColor(new Color(0, 0, 0, 255));
		graphics.fillRect(0, 0, outWidth, outHeight); // Fill with transparency.
		graphics.drawImage(img, oX, oY, mWidth, mHeight, null);
		
		return out;
	}
}
