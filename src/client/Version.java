package client;

import java.util.HashMap;
import java.util.Map;

import common.Util;

/**
 * A simple executable class to print the reference client version.
 *
 * @author gary
 *
 */
public class Version {
	
	public static final Map<String, String> FS2_VERSION_DESCRIPTIONS;
	
	/**
	 * Each version of the fs2 client must be described!
	 * Don't delete old ones when adding new ones.
	 */
	static {
		Map<String, String> map = new HashMap<String, String>();
		map.put("0.10.1", "Access logs, open download buttons and bug fixes!");
		map.put("0.9.5",  "Access logs and better logging.");
		map.put("0.9.4",  "Now with open buttons for downloads and shares.");
		map.put("0.9.3",  "Shares now refresh properly, and source is now included in the .jar file!");
		map.put("0.9.2",  "Disabled automatic indexnode by default until I fix it.");
		map.put("0.9.1",  "Regression with share refreshes locking the client fixed. Autoindexnode negotiation hopefully fixed on unreliable lans.");
		map.put("0.9.0",  "FS2 made much easier to use with the introduction of the settings tab and an internal indexnode!");
		map.put("0.8.27", "Now with working internal indexnode and a fully complete settings tab.");
		map.put("0.8.26", "Settings tab is mostly complete, auto indexnode is also complete. (just need settings for AIN now)");
		map.put("0.8.25", "Good progress being made on the settings tab...");
		map.put("0.8.24", "Settings tab on the way. This is a work in progress. Completion will move to 0.9");
		map.put("0.8.23", "Many bug fixes: launching, tab closing, search removing, average speed calculation, etc.");
		map.put("0.8.22", "Now with extra chat avatars...");
		map.put("0.8.21", "Chat notification in status bar, status bar when no tray icon, fixed popup tray behaviour.");
		map.put("0.8.20", "Serious improvements to reliability and games feature removed. Avatars in chat coming.");
		map.put("0.8.19", "Fixed games tab breaking and chat symbols... added some chat highlights too.");
		map.put("0.8.18", "Fixed autoupdate bug with fully headless clients.");
		map.put("0.8.17", "Fixed games tab null-pointer if the tab isn't opened when there are zero profiles..");
		map.put("0.8.16", "Adds secure mode, games tab, better chat and vast reliability improvements.");
		map.put("0.8.15", "Games tab now with more polish.");
		map.put("0.8.14", "Games tab appears to work now!");
		map.put("0.8.13", "Progress towards a working games tab including pretty table cells.");
		map.put("0.8.12", "Development version towards getting a game viewer working.");
		FS2_VERSION_DESCRIPTIONS = map;
	}
	
	/** The version identifier for the client.
	 * This is used by the client to identify new versions
	 * DONT FORGET TO DESCRIBE THIS VERSION! */
	public static final Integer[] FS2_CLIENT_VERSION_BITS = {0,10,1};
	
	// Into a string:
	public static final String FS2_CLIENT_VERSION() { return Util.join(FS2_CLIENT_VERSION_BITS, "."); }
	
	/** The name of the fs2 client. This is important to the filenaming convention. See UpdateSource.java */
	public static final String FS2_CLIENT_NAME = "fs2client";
	
	/** The fancy release name for this minor version of FS2 */
	public static final String FS2_CLIENT_RELEASE = "It's Made of Paper (i46 edition)";
	
	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.print(FS2_CLIENT_VERSION());
		} else if (args[0].equals("--describe")) {
			System.out.print(FS2_VERSION_DESCRIPTIONS.get(FS2_CLIENT_VERSION()));
		}
	}

}
