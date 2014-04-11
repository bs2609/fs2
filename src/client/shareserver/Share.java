package client.shareserver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import client.platform.Platform;
import common.FS2Constants;
import common.FileList;
import common.FileList.Item;
import common.HttpFileHandler;
import common.HttpUtil;
import common.Logger;
import common.ProgressTracker;
import common.Util;
import common.Util.Deferrable;
import common.Util.NiceMagnitude;
import common.httpserver.HttpContext;

public class Share {
	
	private class Refresher implements Runnable {
		
		volatile boolean shouldStop = false;
		volatile FileCounter fileCounter = null;
		long changed = 0;
		long buildSizeSoFar = 0;
		ProgressTracker tracker = new ProgressTracker();
		
		final DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<Path>() {

			@Override
			public boolean accept(Path entry) throws IOException {
				
				if (entry.getFileName().toString().endsWith(".incomplete")) return false;
				if (Files.isSymbolicLink(entry) && (Files.isDirectory(entry) || !entry.toRealPath().startsWith(canonicalLocation))) return false;
				if (Files.isHidden(entry) && !Files.isDirectory(entry)) return false;
				
				return true;
			}
		};
		
		public synchronized void shutdown() {
			if (fileCounter != null) {
				fileCounter.shutdown();
				fileCounter = null;
			}
			shouldStop = true;
		}
		
		@Override
		public void run() {
			try {
				tracker.setExpectedMaximum(list.root.fileCount);
				
				if (list.root.fileCount == 0L) {
					// We don't have a clue, so set off a counter worker to find out.
					fileCounter = new FileCounter(tracker);
					Thread fct = new Thread(fileCounter);
					fct.setDaemon(true);
					fct.setName("Filecounter for share: " + getName());
					fct.setPriority(Thread.NORM_PRIORITY + 1);
					fct.start();
				}
				refreshActive = true;
				
				if (!location.exists()) {
					setStatus(Status.ERROR);
					cause = ErrorCause.NOTFOUND;
					Logger.warn("Share " + getName() + " (" + location.getName() + ") doesn't exist on disk!");
					return;
				}
				// Start on a canonical file.
				refreshDirectory(canonicalLocation, list.root);
				if (shouldStop) return;
				
				Logger.log("Share '" + getName() + "' is " + (changed > 0 ? "now at revision " + ++list.revision : "unchanged at revision " + list.revision));
				refreshComplete();
				
			} catch (Exception e) {
				Logger.severe("Exception during share refresh: " + e);
				Logger.log(e);
				causeOtherDescription = e.toString();
				setStatus(Status.ERROR);
				cause = ErrorCause.OTHER;
				
			} finally {
				refreshActive = false;
				activeRefresh = null;
			}
		}
		
		void refreshDirectory(final Path directory, Item directoryItem) {
		
			Set<String> existingItems = new HashSet<String>(directoryItem.children.keySet());
			
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, filter)) {
				for (final Path entry : stream) {
				
					if (shouldStop) return;
					Util.executeNeverFasterThan(FS2Constants.CLIENT_EVENT_MIN_INTERVAL, notifyShareServer);
					String entryName = entry.getFileName().toString();
					
					if (existingItems.remove(entryName)) {
						// We already have this item so update it:
						Item existingItem = directoryItem.children.get(entryName);
						long existingSize = existingItem.size;
						long existingFileCount = existingItem.fileCount;
						
						updateItem(entry, existingItem);
						
						directoryItem.size -= existingSize;
						directoryItem.size += existingItem.size;
						directoryItem.fileCount -= existingFileCount;
						directoryItem.fileCount += existingItem.fileCount;
						
					} else {
						changed++;
						// Brand new file or directory.
						Item newItem = new Item();
						newItem.name = entryName;
						directoryItem.children.put(newItem.name, newItem);
						
						if (!updateItem(entry, newItem)) {
							directoryItem.children.remove(newItem.name);
							changed -= 2; // Item couldn't be updated (probably no permission) so this change didn't count.
							              // Nor did the change incurred by the rehash that failed.
						} else {
							directoryItem.size += newItem.size;
							directoryItem.fileCount += newItem.fileCount;
							if (!newItem.isDirectory()) buildSizeSoFar += newItem.size;
						}
					}
					
					if (Files.isRegularFile(entry)) {
						tracker.progress(1); // One more item done.
					}
				}
				
			} catch (DirectoryIteratorException e) {
				Logger.warn("Failed to refresh directory " + directory + " due to " + e.getCause());
				
			} catch (IOException e) {
				Logger.warn("Failed to refresh directory " + directory + " due to " + e);
			}
			
			// Remove files/directories from the list that are still in the 'existing' set,
			// as they are clearly not in the filesystem.
			for (String name : existingItems) {
				changed++; // This must be a change if there are items to remove.
				directoryItem.size -= directoryItem.children.get(name).size;
				directoryItem.fileCount -= directoryItem.children.get(name).fileCount; // this .fileCount should always be one for files.
				directoryItem.children.remove(name);
			}
		}
		
		boolean updateItem(final Path p, Item i) {
			try {
				BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
				
				if (attrs.isDirectory()) {
					if (i.children == null) i.children = new HashMap<String, Item>();
					refreshDirectory(p, i);
					return true;
					
				} else if (attrs.isRegularFile()) {
					boolean shouldHash = false;
					i.fileCount = 1;
					if (i.size != attrs.size()) {
						shouldHash = true;
						i.size = attrs.size();
					}
					if (i.lastModified != attrs.lastModifiedTime().toMillis()) {
						shouldHash = true;
						i.lastModified = attrs.lastModifiedTime().toMillis();
					}
					if (i.hashVersion != FS2Constants.FILE_DIGEST_VERSION_INT) {
						shouldHash = true;
						i.hashVersion = FS2Constants.FILE_DIGEST_VERSION_INT;
					}
					if (shouldHash || i.hash.equals("")) {
						changed++;
						return calculateHash(p, i);
					}
					return true;
				}
				
			} catch (IOException e) {
				Logger.warn("Failed to update details for " + p.getFileName() + ": " + e);
				return false;
			}
			return false;
		}
		
		boolean calculateHash(final Path p, Item i) {
			try {
				i.hash = ThrottledFileDigester.fs2DigestFile(p, null);
				return true;
				
			} catch (Exception e) {
				Logger.warn("Failed to generate hash for " + p.getFileName() + ", " + e);
				Logger.log(e);
				return false;
			}
		}
		
		/**
		 * A file counter which just recurses directories in order to find out how many
		 * files are within it.
		 * @author r4abigman
		 */
		private class FileCounter implements Runnable {

			volatile boolean         shouldStop = false;
			private  int             fileCount  = 0;
			private  ProgressTracker tracker;
			
			public FileCounter(ProgressTracker tracker) {
				this.tracker = tracker;
			}
			
			public synchronized void shutdown() {
				shouldStop = true;
			}
			
			@Override
			public void run() {
				try {
					if (shouldStop) return;
					// Start on a canonical file.
					countDirectory(canonicalLocation);
					
				} catch (Exception e) {
					Logger.severe("Exception during file count: " + e);
					Logger.log(e);
					// As something went wrong, just set the max expected to zero.
					tracker.setExpectedMaximum(0);
					
				} finally {
					fileCounter = null;
				}
			}
			
			void countDirectory(final Path directory) {
				
				class FileCounterVisitor<T> extends SimpleFileVisitor<T> {
					
					@Override
					public FileVisitResult visitFile(T file, BasicFileAttributes attrs) throws IOException {
						if (shouldStop) return FileVisitResult.TERMINATE;
						Util.executeNeverFasterThan(FS2Constants.CLIENT_EVENT_MIN_INTERVAL, notifyShareServer);
						fileCount++;
						return FileVisitResult.CONTINUE;
					}
					
					@Override
					public FileVisitResult postVisitDirectory(T dir, IOException exc) throws IOException {
						tracker.setExpectedMaximum(fileCount);
						return FileVisitResult.CONTINUE;
					}
				}
				
				try {
					Files.walkFileTree(directory, new FileCounterVisitor<Path>());
					
				} catch (IOException e) {
					Logger.warn("Unable to count all files in " + directory + ": " + e);
				}
			}
			
		}
		
	}
	
	public enum Status {ACTIVE, REFRESHING, BUILDING, ERROR, SHUTDOWN, SAVING};
	public enum ErrorCause {NOTFOUND, UNSAVEABLE, OTHER};
	Status status = Status.BUILDING;
	ErrorCause cause = ErrorCause.OTHER;
	String causeOtherDescription;
	private File location;
	Path canonicalLocation;
	FileList list; // The structure that holds the list of files.
	Path listFile;
	ShareServer ssvr;
	HttpContext context;
	
	volatile Refresher activeRefresh;
	volatile boolean refreshActive = false;
	
	private class NotifyShareServer implements Deferrable {
		
		@Override
		public void run() {
			ssvr.notifyShareChanged(Share.this);
		}
	}
	
	private NotifyShareServer notifyShareServer = new NotifyShareServer();
	
	public Share(ShareServer ssvr, String name, File location) throws IOException {
		
		this.location = location;
		this.canonicalLocation = location.toPath().toRealPath();
		this.ssvr = ssvr;
		listFile = Platform.getPlatformFile("filelists" + File.separator + name + ".FileList").toPath();
		
		if (Files.exists(listFile)) {
			InputStream is = new BufferedInputStream(Files.newInputStream(listFile));
			list = FileList.reconstruct(is);
			is.close();
		}
		
		// The user might (for some reason) have changed the case of the share name, so update the filelist now:
		if (list != null && !list.getName().equals(name)) {
			Logger.log("Share name doesn't match FileList's internal name... updating.");
			list.root.name = name;
			saveList();
		}
		
		if (list == null) {
			// Create a new list from scratch.
			list = FileList.newFileList(name);
		}
		
		context = ssvr.getHttpServer().createContext("/shares/" + HttpUtil.urlEncode(name),
			new HttpFileHandler(location, ssvr.getHttpEventsHandler(), ssvr.getUploadTracker()));
		
		context.getFilters().add(ssvr.getSecureFilter());
		context.getFilters().add(ssvr.getFS2Filter());
		context.getFilters().add(ssvr.getQueueFilter());
		context.getFilters().add(ssvr.getThrottleFilter());
		
		// If we just created a new filelist then the share must be built, else just refreshed.
		if (list.revision == 0) {
			Logger.log("Share '" + name + "' is being built for the first time.");
			scheduleRefresh(true);
		} else {
			setStatus(Status.ACTIVE);
		}
	}
	
	/**
	 * Returns the size of this share.
	 * @return
	 */
	public long getSize() {
		switch (status) {
		case BUILDING:
		case REFRESHING:
			return Math.max(activeRefresh != null ? activeRefresh.buildSizeSoFar : 0L, list.root.size);
		default:
			return list.root.size;
		}
	}
	
	/**
	 * Returns the number of files in this share.
	 * @return
	 */
	public long getFileCount() {
		switch (status) {
		case BUILDING:
		case REFRESHING:
			return Math.max(activeRefresh != null ? activeRefresh.tracker.getPosition() : 0L, list.root.fileCount);
		default:
			return list.root.fileCount;
		}
	}
	
	public String describeStatus() {
		switch (status) {
		case BUILDING:
			if (activeRefresh != null) {
				ProgressTracker tr = activeRefresh.tracker;
				String msg = "Building";
				if (!refreshActive) {
					msg += " (queued)";
				} else {
					msg += " at " + new NiceMagnitude((long) tr.getSpeed(), "") + " files/s";
					if (tr.getMaximum() > tr.getPosition()) {
						// Maximum expected is actually set, meaning we must have scanned for file counts.
						msg += ", ETR: " + tr.describeTimeRemaining();
					}
				}
				return msg;
			}
		case REFRESHING:
			if (activeRefresh != null) {
				ProgressTracker tr = activeRefresh.tracker;
				return tr.percentCompleteString() + " refreshed " + (refreshActive ? "at " + new NiceMagnitude((long) tr.getSpeed(), "") + " files/s, ETR: " + tr.describeTimeRemaining() : "(queued)");
			}
		case ERROR:
			return describeError();
		case ACTIVE:
			return "Active";
		case SAVING:
			return "Saving";
		default:
			return status.toString().toLowerCase();
		}
	}
	
	public String describeError() {
		switch (cause) {
		case NOTFOUND:
			return "Error: not found on disk";
		case UNSAVEABLE:
			return "Error: file list unsaveable: " + causeOtherDescription;
		default:
			return "Error: " + causeOtherDescription;
		}
	}
	
	/**
	 * Schedules this share to be refreshed.
	 */
	public void refresh() {
		scheduleRefresh(false);
	}
	
	/**
	 * Schedule this share to be refreshed when there is space in the refresh pool.
	 * @param firstRefresh specify true iff this is the initial refresh.
	 */
	private synchronized void scheduleRefresh(boolean firstRefresh) {
		// Can't refresh a shutdown share.
		synchronized (status) { if (status == Status.SHUTDOWN) return; }
		// Only do something if there is no active/scheduled refresher already.
		if (activeRefresh != null) return;
		
		if (!firstRefresh) setStatus(Status.REFRESHING);
		activeRefresh = new Refresher();
		ssvr.getShareRefreshPool().execute(activeRefresh);
	}
	
	private void refreshComplete() {
		list.setRefreshedNow();
		if (saveList()) setStatus(Status.ACTIVE);
		// Does not return immediately.
		ssvr.getIndexNodeCommunicator().sharesChanged();
	}
	
	public synchronized void shutdown() {
		if (activeRefresh != null) {
			activeRefresh.shutdown();
			activeRefresh = null;
		}
		ssvr.getHttpServer().removeContext(context);
		setStatus(Status.SHUTDOWN);
	}
	
	private boolean saveList() {
		try {
			setStatus(Status.SAVING);
			Path partial = listFile.resolveSibling(listFile.getFileName() + ".working");
			Files.deleteIfExists(partial);
			
			OutputStream os = new BufferedOutputStream(Files.newOutputStream(partial));
			list.deconstruct(os);
			os.close();
			
			Files.deleteIfExists(listFile);
			Files.move(partial, listFile);
			return true;
			
		} catch (IOException e) {
			Logger.severe("Share filelist couldn't be saved: " + e);
			Logger.log(e);
			causeOtherDescription = e.toString();
			cause = ErrorCause.UNSAVEABLE;
			setStatus(Status.ERROR);
			return false;
		}
	}
	
	public String getName() {
		return list.getName();
	}
	
	public int getRevision() {
		return list.revision;
	}
	
	private void setStatus(Status newStatus) {
		synchronized (status) {
			if (status == newStatus) return;
			status = newStatus;
			Logger.log("Share '" + getName() + "' became " + status);
			if (status != Status.SHUTDOWN) ssvr.notifyShareChanged(this);
		}
	}
	
	public Status getStatus() {
		return status;
	}
	
	public void setPath(File path) throws IOException {
		location = path;
		canonicalLocation = path.toPath().toRealPath();
		refresh();
	}
	
	/**
	 * Returns the timestamp of when this share was last successfully refreshed.
	 * @return
	 */
	public long getLastRefreshed() {
		return list.getLastRefreshed();
	}
	
	/**
	 * Returns the path that this shares.
	 * @return
	 */
	public File getPath() {
		return location;
	}
	
}
