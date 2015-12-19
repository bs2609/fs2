package client.platform;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import common.FS2Constants;
import common.Logger;
import common.Util;

import client.ClientExecutor;
import client.platform.updatesources.CacheSource;
import client.platform.updatesources.CodeUpdate;

/**
 * Contains methods used to relaunch FS2, either to updated, cached code or to increase the java heapsize.
 * 
 * FOR UPDATES:
 * Determines if there is a newer version of FS2 in the code updates storage, and if there is, that version is executed.
 * It's quite important that this code is correct as the oldest version installed on a system is the one that is used:
 * It is never 'overwritten' by updates.
 * 
 * FOR JVM HEAP:
 * Attemps to relaunch the jvm with more heap, this code is updated by the autoupdater as it is invoked later in the startup process.
 * 
 * @author gary
 */
public class Relauncher {

	/**
	 * Relaunches newer code if it exists.
	 * Returns true if a new instance was launched.
	 * 
	 * This can safely be called at anytime. (It will invoke ClientExecutor's shutdown)
	 * 
	 * @return True if an updated version was launched.
	 */
	public static boolean go() {
		CodeUpdate update = (new CacheSource()).getLatestUpdate();
		if (update==null) {
			Logger.log("There are no newer versions of FS2 in the update cache.");
		} else {
			return relaunch(update.location);
		}
		return false;
	}
	
	private static boolean relaunch(URL newJar) {
		Logger.log("Attempting to start a newer version of FS2... (" + newJar + ")");
		try (URLClassLoader loader = new URLClassLoader(new URL[] {newJar}, null)) {
			//                    __/-This is used so that automatic refactoring might work in future if the entry point is changed.
			Class<?> ce = loader.loadClass(ClientExecutor.class.getName());
			Method init = ce.getDeclaredMethod("main", String[].class);
			// Shutdown the current instance:
			ClientExecutor.shutdown();
			// Now launch the new instance:
			init.invoke(null, (Object) ClientExecutor.args);
			return true;
			
		} catch (Exception e) {
			Logger.severe("The update is corrupt or incompatible with this version of FS2.");
			Logger.warn("This version of FS2 will continue to execute.");
			Logger.log(e);
			return false;
		}
	}
	
	/**
	 * Attempts to relaunch the JVM and FS2 with the specified new heap size.
	 * 
	 * The returns of this method are subtle:
	 * 
	 * false for outright instant failure: such as trying to relaunch not from a jar, or if the JVM is missing.
	 * true if the execution started without exception.
	 * 
	 * @param newHeapSize - The new heap size, in bytes.
	 * @param inheritIO - If true then the new process will inherit this process's IO streams. Otherwise these get discarded.
	 * 
	 */
	public static boolean increaseHeap(long newHeapSize, boolean inheritIO) {
		try {
			String jvmPath = Platform.getCurrentPlatformOS().getJVMExecutablePath(); 
			
			// Determine which jar we are executing within:
			String thisJarPath = new File(Relauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getAbsolutePath();
			
			// Ensure running in a jar and not a debug environment.:
			if (!thisJarPath.toLowerCase().endsWith(".jar")) {
				return false;
			}
			
			long oldHeapSize = Runtime.getRuntime().maxMemory();
			
			Logger.log("Attempting to relaunch JVM with more heap...");
			Logger.log("( " + Util.niceSize(oldHeapSize) + " -> " + Util.niceSize(newHeapSize) + " )");
			
			ClientExecutor.shutdown();
			
			List<String> args = new ArrayList<String>();
			
			args.add(jvmPath);
			args.add("-Xmx" + newHeapSize);
			
			// Copy Java properties to new JVM:
			for (String property : FS2Constants.CLIENT_IMPORTANT_SYSTEM_PROPERTIES) {
				String propertyValue = System.getProperty(property);
				if (propertyValue != null) {
					args.add("-D" + property + "=" + propertyValue);
				}
			}
			
			// Add a new system property to prevent looping restarts:
			if (inheritIO) {
				args.add("-Dincreasedheap");
			}
			
			args.add("-jar");
			args.add(thisJarPath);
			
			ProcessBuilder newJVM = new ProcessBuilder(args);
			if (inheritIO) {
				newJVM.inheritIO();
			}
			newJVM.start();
			
			return true;
			
		} catch (Exception e) {
			Logger.severe("Couldn't relaunch the JVM to increase the heapsize.");
			Logger.log("This version of FS2 will continue to execute.");
			Logger.log(e);
			return false;
		}
	}

}
