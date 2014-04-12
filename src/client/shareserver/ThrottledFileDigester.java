package client.shareserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import common.FS2Constants;
import common.ProgressTracker;
import common.Util.ByteArray;

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

	public static ByteArray digest(InputStream input, BandwidthSharer bs, String algorithm, byte[] extra, ProgressTracker tracker) throws NoSuchAlgorithmException, IOException {
		if (bs != null) input = new ThrottledInputStream(input, bs);
		try (DigestInputStream digester = new DigestInputStream(input, MessageDigest.getInstance(algorithm))) {
			byte[] buf = new byte[FS2Constants.ARBITRARY_BUFFER_SIZE];
			int read = 0;
			while ((read = digester.read(buf)) > 0) {
				if (tracker != null) tracker.progress(read);
			}
			return new ByteArray(digester.getMessageDigest().digest(extra));
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
	public static ByteArray fs2DigestFile(File file, BandwidthSharer bs) throws NoSuchAlgorithmException, IOException {
		return fs2DigestFile(file, bs, null);
	}
	
	public static ByteArray fs2TrackableDigestFile(File file, ProgressTracker tracker) throws NoSuchAlgorithmException, IOException {
		return fs2DigestFile(file, null, tracker);
	}
	
	public static ByteArray fs2DigestFile(File file, BandwidthSharer bs, ProgressTracker tracker) throws NoSuchAlgorithmException, IOException {
		return digest(new FileCropperStream(file, FS2Constants.FILE_DIGEST_HEAD_FOOT_LENGTH), bs, FS2Constants.FILE_DIGEST_ALGORITHM, BigInteger.valueOf(file.length()).toByteArray(), tracker);
	}
}
