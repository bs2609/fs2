package client.shareserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import common.FS2Constants;
import common.ProgressTracker;
import common.Util;

/**
 * Provides Java with the ability to produce digests of files but at a throttled rate.
 * Provide null for the BandwidthSharer and then it will not throttle the digestion.
 * 
 * It will also append the extra bytes to the end of the file bytes before digesting.
 * This can be used as an additional seed.
 * 
 * This is useful if a lot of files require digesting and the computer should not be monopolised.
 * (IONice and Nice should probably be used in preference to a stringent throttle though)
 * @author gary
 */
public class ThrottledFileDigester {

	public static String digest(InputStream input, BandwidthSharer bs, String algorithm, byte[] extra, ProgressTracker tracker) throws NoSuchAlgorithmException, IOException {
		if (bs != null) input = new ThrottledInputStream(input, bs);
		try (DigestInputStream digester = new DigestInputStream(input, MessageDigest.getInstance(algorithm))) {
			byte[] buf = new byte[FS2Constants.ARBITRARY_BUFFER_SIZE];
			int read = 0;
			while ((read = digester.read(buf)) > 0) {
				if (tracker != null) tracker.progress(read);
			}
			return Util.bytesToHexString(digester.getMessageDigest().digest(extra));
		}
	}

	/**
	 * Generates and returns the fs2 digest of a file.
	 * @param file
	 * @param bs The BandwidthSharer to throttle the digesting. Use null for unlimited.
	 * @return The fs2 digest of the file as a string.
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static String fs2DigestFile(File file, BandwidthSharer bs) throws NoSuchAlgorithmException, IOException {
		return fs2DigestFile(file, bs, null);
	}
	
	public static String fs2TrackableDigestFile(File file, ProgressTracker tracker) throws NoSuchAlgorithmException, IOException {
		return fs2DigestFile(file, null, tracker);
	}
	
	public static String fs2DigestFile(File file, BandwidthSharer bs, ProgressTracker tracker) throws NoSuchAlgorithmException, IOException {
		return digest(new FileCropperStream(file, FS2Constants.FILE_DIGEST_HEAD_FOOT_LENGTH), bs, FS2Constants.FILE_DIGEST_ALGORITHM, Long.toString(file.length()).getBytes("UTF-8"), tracker);
	}
}
