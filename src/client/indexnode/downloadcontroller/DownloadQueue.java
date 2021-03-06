package client.indexnode.downloadcontroller;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import client.gui.Utilities;
import client.indexnode.FileSystemEntry;
import client.indexnode.IndexNodeCommunicator;
import client.indexnode.ListableEntry;
import client.indexnode.NewPeerListener;
import client.platform.Platform;

import common.FS2Constants;
import common.Logger;
import common.SafeSaver;
import common.SafeSaver.Savable;
import common.Util;
import common.Util.ByteArray;
import common.Util.Deferrable;
import common.Util.Filter;
import common.Util.LockHolder;

/**
 * Represents the queue of downloads waiting to happen.
 * 
 * While this is structured as a tree, the root is meaningless.
 * Each child of the root represents the root of a different download location on this computer.
 * 
 * One of the design goals of this class is to minimise memory consumption for a latent queue
 * and facilitate smart dispatch of the queued files to download workers.
 * 
 * This class will process all changes to its model for the tree in a Swing thread.
 * All calls that require access to the model should occur in a Swing thread or a deadlock may occur.
 * 
 * @author gary
 */
public class DownloadQueue implements Serializable, TreeModel, Savable, NewPeerListener {
	
	private static final long serialVersionUID = 2630705870997404062L;
	
	// Nested classes:
	
	/** Notifies a listener that progress is occurring on download item submission. */
	public interface DownloadSubmissionListener {
		
		/**
		 * Notify a listener that a file has been successfully queued.
		 * This will be dispatched in a Swing thread.
		 * @param file The file that was queued.
		 */
		void fileSubmitted(FileSystemEntry file);
		
		/**
		 * Returns true if this enqueueing operation has been cancelled by the user.
		 * This will not be dispatched!
		 * @return
		 */
		boolean isCancelled();
		
		/**
		 * Notifies the callee that the submission has been completed.
		 * Dispatched in Swing thread.
		 */
		void complete();
		
	}
	
	public abstract class DownloadItem implements Serializable, TreeNode {
		
		private static final long serialVersionUID = 8940688192984740903L;
		
		DownloadItem parent;
		
		abstract public String getName();
		
		/**
		 * Removes the specified child from this if this is a container.
		 * @param child the child to remove.
		 * @param deferrable A hint indicating (if true) that the event may be cached and executed as a batch at some point in the future.
		 */
		abstract void removeChild(DownloadItem child, boolean deferrable); // Used only internally.
		
		/** Cancels this download item, stops downloads if they are contained within or are this item. */
		public void cancel() {
			dc.dispatch.queueItemCancelled(this);
			parent.removeChild(this, true);
			dc.recalculateRemaining();
		}
		
		@Override
		public TreeNode getParent() {
			return parent;
		}
		
		/**
		 * Resets this item (recursively) to a new dispatch ID.
		 * This means that this item will no longer be considered as necessarily sharing peers with the current dispatch ID.
		 */
		public void resetDispatchId() {
			resetDispatchId(nextDispatchId.getAndIncrement());
		}
		
		/**
		 * Package private, resets this item to the specified dispatch ID.
		 * @param newId
		 */
		abstract void resetDispatchId(int newId);
		
		transient TreePath cachedPath;
		
		/**
		 * Gets the tree path that represents the path to this node from the root of the tree model.
		 * @return the path to this queue node in the tree model.
		 */
		public TreePath getPath() {
			if (cachedPath != null) return cachedPath;
			Deque<Object> path = new ArrayDeque<Object>();
			
			path.push(this);
			
			TreeNode p = parent;
			while (p != null) {
				path.push(p);
				p = p.getParent();
			}
			
			cachedPath = new TreePath(path.toArray());
			return cachedPath;
		}
		
		@Override
		public String toString() {
			return getName();
		}
		
		/** Notifies a GUI that this item has changed in the tree. This is rate limited. */
		void updateThis() {
			throttledTreeNodeChanged(this);
		}
		
		/**
		 * Returns the file that would represent this queue item on disk.
		 * @return
		 */
		public abstract File getFile();
		
		transient ReadWriteLock rwlock;
		
		/**
		 * Returns a read-write lock for the download item.
		 * This is only intended for use for items that have children so may be structurally altered.
		 * 
		 * A file may throw an unchecked exception if this is called.
		 * @return
		 */
		ReadWriteLock getReadWriteLock() {
			return (rwlock == null ? rwlock = new ReentrantReadWriteLock() : rwlock);
		}
		
		/**
		 * Moves this item to the head of the download queue,
		 * and resets the iterator to consider the queue in order again.
		 */
		public void promote() {
			// 1) Remove from the parent:
			parent.removeChild(this, false); // Can't be deferred.
			DownloadItem oldParent = parent;
			parent = null;
			
			destroyPathCaches();
			
			// 2) Let the subclass decide how to re-add itself to the queue. This must remember to update this node's parent!
			_promote(oldParent);
			
			// 3) Reset the iterator.
			iterationIdx++;
			setupQueueIterator();
		}
		
		/**
		 * This is intended to recursively remove cached treepaths.
		 * @param item
		 */
		protected void destroyPathCaches() {
			cachedPath = null;
		}
		
		/** Adds this item back to the top of the queue. */
		public abstract void _promote(DownloadItem oldParent);
	}
	
	/**
	 * Represents a download directory, these contain other download directories and files to actually download.
	 * These should not be left here if empty! (with the exception of the default download directory)
	 * @author gary
	 */
	public class DownloadDirectory extends DownloadItem {
		
		private static final long serialVersionUID = 2811703048882673787L;
		
		File path;
		
		@Override
		public File getFile() {
			return path;
		}
		
		/** This is used to drive the tree model only, this is always an indexed mirror of children. */
		transient List<DownloadItem> modelChildren; // It's also used as the mutex for modification of the children map.
		transient ModelRemovalUpdater modelRemovalUpdater;
		
		/**
		 * Used when recursively resetting dispatch IDs.
		 * @param newId
		 */
		@Override
		void resetDispatchId(int newId) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
				for (DownloadItem i : children.values()) {
					i.resetDispatchId(newId);
				}
			}
		}
		
		/** The actual children structure used by us internally. */
		LinkedHashMap<String, DownloadItem> children = new LinkedHashMap<String, DownloadItem>();
		
		public DownloadDirectory(File path, DownloadItem parent) {
			setup();
			this.parent = parent;
			this.path = path;
			path.mkdirs();
		}

		private void writeObject(ObjectOutputStream out) throws IOException {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				out.defaultWriteObject();
			}
		}
		
		/**
		 * Gets the child directory with the name specified, or creates a new empty one otherwise.
		 * If the named child existed already but was not a directory then null is returned.
		 * 
		 * @param name
		 * @return
		 */
		public DownloadDirectory getChildDirectory(String name) {
			ChildDirectoryGetter cdg = new ChildDirectoryGetter(name);
			Utilities.edispatch(cdg);
			return cdg.result;
		}
		
		/**
		 * A task to be run in the Swing thread, to find or create a child directory.
		 * @author gp
		 */
		private class ChildDirectoryGetter implements Runnable {
			
			String name;
			DownloadDirectory result;
			
			ChildDirectoryGetter(String name) {
				this.name = name;
			}
			
			@Override
			public void run() {
				DownloadDirectory d;
				int idx;
				try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
					if (children.containsKey(name)) {
						DownloadItem item = children.get(name);
						if (item instanceof DownloadDirectory) {
							result = (DownloadDirectory) item; 
						} else {
							result = null;
						}
						return;
					}
					d = new DownloadDirectory(new File(path.getAbsolutePath() + File.separator + name), DownloadDirectory.this);
					children.put(d.getName(), d);
					treeModified = true;
					modelChildren.add(d);
					idx = modelChildren.size() - 1;
				}
				fireTreeNodesInserted(new TreeModelEvent(DownloadQueue.this, getPath(), new int[] {idx}, new Object[] {d}));
				result = d;
			}
		}
		
		private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
			in.defaultReadObject();
			setup();
		}
		
		private void setup() {
			modelChildren = new ArrayList<DownloadItem>(children.values());
			modelRemovalUpdater = new ModelRemovalUpdater();
		}
	
		/**
		 * Submits a collection of filesystem entries. Files will be submitted for downloading to this directory,
		 * directories will be created as subdownloaddirectories and their contents looked up and added recursively.
		 * @param files A collection of file system entries to add, directories will be recursed into.
		 * @param listener a submission listener to be notified when a file is added to the queue.
		 */
		public void submit(Collection<FileSystemEntry> toQueue, DownloadSubmissionListener listener, final Integer dispatchId) {
			for (final FileSystemEntry entry : toQueue) {
				if (listener.isCancelled()) return; // Stop now if cancelled.
				
				if (entry.isDirectory()) {
					DownloadDirectory item = getChildDirectory(entry.getName());
					
					if (item == null) {
						Logger.log("Submitting directory to download queue that would overwrite file, ignoring.");
						continue; // It already exists, but was a file (this is expected to be _RARE_)
					}
					
					// Now recurse...
					// Fetch the next children from the indexnode.
					List<FileSystemEntry> childs = comm.lookupChildren(entry);
					((DownloadDirectory) item).submit(childs, listener, dispatchId);
					// Cull this directory if it is empty after submission.
					if (item.children.size() == 0) removeChild(item, false);
					
				} else {
					try {
						Utilities.edispatch(new Runnable() {
							@Override
							public void run() {
								DownloadFile f;
								int idx;
								try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
									if (children.containsKey(entry.getName())) return; // Don't re-add pre-existing files.
									f = new DownloadFile(entry.getName(), entry.getHash(), entry.getSize(), DownloadDirectory.this, dispatchId);
									dc.allDownload.expandTask(entry.getSize());
									children.put(f.getName(), f);
									treeModified = true;
									modelChildren.add(f);
									idx = modelChildren.size() - 1;
								}
								fireTreeNodesInserted(new TreeModelEvent(DownloadQueue.this, getPath(), new int[] {idx}, new Object[] {f}));
							}
						});
					} finally {
						fireFileSubmittedEvent(entry, listener);
					}
				}
			}
			saver.requestSave();
		}
		
		private void fireFileSubmittedEvent(final FileSystemEntry f, final DownloadSubmissionListener listener) {
			try {
				Utilities.dispatch(new Runnable() {
					@Override
					public void run() {
						listener.fileSubmitted(f);
					}
				}, false);
				
			} catch (Exception e) {
				Logger.warn("Couldn't dispatch file submission event: " + e);
				Logger.log(e);
			}
		}
		
		/**
		 * Removes the specified child from this directory, but does not necessarily update the treemodel now.
		 * This is because updating the treemodel too frequently is wasteful and costly.
		 * @param info
		 */
		@Override
		public void removeChild(DownloadItem child, boolean deferrable) {
			removeChild(child, true, deferrable);
		}
		
		void removeChild(DownloadItem child, boolean removeEmptyAncestors, boolean deferrable) {
			boolean cascade = false;
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
				children.remove(child.getName());
				treeModified = true;
				if (children.isEmpty() && removeEmptyAncestors) {
					cascade = true;
				}
			}
			if (cascade) parent.removeChild(this, deferrable); // cascade
			
			if (deferrable)
				Util.scheduleExecuteNeverFasterThan(FS2Constants.CLIENT_EVENT_MIN_INTERVAL, modelRemovalUpdater);
			else {
				modelRemovalUpdater.run(); // If it can't be deferred then run now.
			}
			saver.requestSave();
		}
		
		private class ModelRemovalUpdater implements Deferrable {
			@Override
			public void run() {
				Utilities.edispatch(new Runnable() {
					@Override
					public void run() {
						// 1) Build a list of removed items:
						final List<Integer> ints = new ArrayList<Integer>();
						final List<TreeNode> items = new ArrayList<TreeNode>();
						final int[] indices;
						
						try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
							for (int i = 0; i < modelChildren.size(); i++) {
								if (!children.containsKey(modelChildren.get(i).getName())) {
									ints.add(i);
									items.add(modelChildren.get(i));
								}
							}
							
							indices = new int[ints.size()];
							int ix = 0;
							for (Integer i : ints) indices[ix++] = i; 
							
							// 2) Rebuild model children:
							modelChildren.clear();
							modelChildren.addAll(children.values());
						}
						
						// 3) Issue removal events:
						fireTreeNodesRemoved(new TreeModelEvent(DownloadQueue.this, getPath(), indices, items.toArray()));
					}
				});
			}
		}
		
		@Override
		public String getName() {
			return path.getName();
		}
		
		@Override
		public Enumeration<TreeNode> children() {
			return new Util.EnumerationWrapper<TreeNode>(new ArrayList<TreeNode>(modelChildren));
		}
		
		@Override
		public boolean getAllowsChildren() {
			return true;
		}
		
		@Override
		public TreeNode getChildAt(int childIndex) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				return modelChildren.get(childIndex);
			}
		}
		
		@Override
		public int getIndex(TreeNode node) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				return modelChildren.indexOf(node);
			}
		}
		
		@Override
		public int getChildCount() {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				return modelChildren.size();
			}
		}
		
		@Override
		public boolean isLeaf() {
			return false;
		}

		/**
		 * Adds the specified file to the start of this directory.
		 * This is linear complexity due to the structures needed for constant-time random access lookup.
		 * @param file
		 */
		public void addFileFirst(final DownloadFile file) {
			Utilities.edispatch(new Runnable() {
				@Override
				public void run() {
					try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
						file.parent = DownloadDirectory.this;
						// 1) Update model:
						ArrayList<DownloadItem> newModelChildren = new ArrayList<DownloadItem>();
						newModelChildren.add(file);
						newModelChildren.addAll(modelChildren);
						modelChildren = newModelChildren;
						
						// 2) Update actual children:
						LinkedHashMap<String, DownloadItem> newChildren = new LinkedHashMap<String, DownloadItem>();
						newChildren.put(file.getName(), file);
						newChildren.putAll(children);
						children = newChildren;
					}
					fireTreeNodesInserted(new TreeModelEvent(DownloadQueue.this, getPath(), new int[] {0}, new Object[] {file}));
				}
			});
		}

		@Override
		public void _promote(DownloadItem oldParent) {
			root.addDirectoryFirst(this);
		}
		
		@Override
		protected void destroyPathCaches() {
			super.destroyPathCaches();
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				for (DownloadItem item : children.values()) item.destroyPathCaches();
			}
		}
	}
	
	public class DownloadFile extends DownloadItem {
		
		private static final long serialVersionUID = 6678427711829848635L;
		
		private String saveAs;
		ByteArray hash;
		long size;
		
		@Override
		public File getFile() {
			return new File(parent.getFile().getAbsoluteFile() + File.separator + saveAs);
		}
		
		/**
		 * Provides the download information for this file if it is currently active.
		 * If this field is null the download has never been started.
		 * If this field if non-null then the download is active, partially complete or complete.
		 * (which may be determined by inspecting the 'worker' field of the DownloadInfo)
		 */
		DownloadInfo active;
		
		/**
		 * When downloads are queued the batch operation that they were queued with has a single ID.
		 * Under the (weak!) assumption that every peer will have every file from this batch if they have any,
		 * this allows us to ignore all files with a certain dispatchID if any with this ID were queued.
		 */
		Integer dispatchID;
		
		/**
		 * Used to prevent the same download file from being returned from an iteration repeatedly.
		 * This allows the iterator to skip downloads that have already been considered once this iteration.
		 */
		transient int lastIterationIdx;
		
		@Override
		void resetDispatchId(int newId) {
			this.dispatchID = newId;
			this.updateThis();
		}
		
		void notifyNoSources() {
			DownloadQueue.this.notifyNoSources(this);
		}
		
		public boolean isError() {
			return active != null && active.error;
		}
		
		public String getErrorDescription() {
			return isError() && active.errorDescription != null ? active.errorDescription : "";
		}
		
		public boolean hasNoSources() {
			return noSourceDispatches.contains(dispatchID);
		}
		
		public DownloadFile(String name, ByteArray hash, long size, DownloadItem parent, Integer dispatchId) {
			this.saveAs = name;
			this.hash = hash;
			this.parent = parent;
			this.size = size;
			this.dispatchID = dispatchId;
		}
		
		/**
		 * Removes this download file from its parent directory.
		 */
		public void downloadComplete() {
			parent.removeChild(this, true);
			DownloadInfo info = active;
			fireDownloadCompleteEvent(this);
			dc.allDownload.expandTask(info != null ? -info.bytesRemaining() : -size);
		}
		
		@Override
		public void removeChild(DownloadItem child, boolean deferrable) {
			throw new UnsupportedOperationException("Download files do not have children.");
		}
		
		@Override
		public String getName() {
			return saveAs;
		}
		
		@Override
		public Enumeration<TreeNode> children() {
			return new Enumeration<TreeNode>() {
				public boolean hasMoreElements() { return false; };
				public TreeNode nextElement() { return null; };
			};
		}
		
		@Override
		public boolean getAllowsChildren() {
			return false;
		}
		
		@Override
		public TreeNode getChildAt(int childIndex) {
			return null;
		}
		
		@Override
		public int getChildCount() {
			return 0;
		}
		
		@Override
		public int getIndex(TreeNode node) {
			return -1;
		}
		
		@Override
		public boolean isLeaf() {
			return true;
		}
		
		public Integer getDispatchID() {
			return dispatchID;
		}
		
		public boolean isDownloading() {
			return active != null && active.worker != null;
		}
		
		/**
		 * True if the download is active and only has secure chunks
		 * @return
		 */
		public boolean isSecure() {
			return isDownloading() && active.worker.isSecure();
		}
		
		public String describeProgress() {
			try {
				if (!isDownloading()) return "";
				return active.worker.getActiveChunkCount() + " active chunks, " + active.worker.info.fileProgress.describe();
				
			} catch (NullPointerException e) {
				return "<inactive>"; // Will occur if the worker in question terminates during this method execution. (not as unlikely as it sounds)
			}
		}
		
		private transient String nameCache;
		
		@Override
		void updateThis() {
			generateNameCache();
			super.updateThis();
		}

		private void generateNameCache() {
			if (this.hasNoSources()) {
				nameCache = (getName() + " (waiting for sources in group: " + this.getDispatchID() + ")");
			} else if (this.isError()) {
				nameCache = (getName() + " (" + this.getErrorDescription() + ")");
			} else if (this.isDownloading()) { 
				nameCache = (getName() + " (" + this.describeProgress() + ")");
			} else nameCache = getName();
		}
		
		@Override
		public String toString() {
			if (nameCache == null) generateNameCache();
			return nameCache;
		}

		public long getSize() {
			return size;
		}

		/**
		 * Throw an exception because this should never be called.
		 */
		@Override
		ReadWriteLock getReadWriteLock() {
			throw new UnsupportedOperationException("Download files do not have children, so do not support locks.");
		}
		
		@Override
		public void _promote(DownloadItem oldParent) {
			root.addFileFirst(this, oldParent.getFile());
		}
	}

	/**
	 * Represents the root of all download directories.
	 * @author gary
	 */
	private class QueueRoot extends DownloadItem {
		
		private static final long serialVersionUID = 5714572089782107069L;
		
		/** This is essentially the list of top level download directories. */
		ArrayList<DownloadDirectory> downloadDirs = new ArrayList<DownloadDirectory>();
		
		private void writeObject(ObjectOutputStream out) throws IOException {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				out.defaultWriteObject();
			}
		}
		
		/**
		 * Removes the child specified (which must be a download directory)
		 * and updates the treemodel immediately.
		 */
		@Override
		public void removeChild(final DownloadItem child, boolean deferrable) {
			Utilities.edispatch(new Runnable() {
				@Override
				public void run() {
					final int dirI;
					try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
						dirI = downloadDirs.indexOf(child);
						if (dirI < 0) {
							Logger.warn("Attempt to remove a queueroot child that doesn't exist...");
							return;
						}
						downloadDirs.remove(dirI);
						treeModified = true;
						saver.requestSave();
					}
					fireTreeNodesRemoved(new TreeModelEvent(DownloadQueue.this, QueueRoot.this.getPath(), new int[] {dirI}, new TreeNode[] {child}));
				}
			});
		}
		
		/**
		 * Promotes a download directory to be a top-level download directory.
		 * @param dir
		 */
		void addDirectoryFirst(DownloadDirectory dir) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
				ArrayList<DownloadDirectory> newdlds = new ArrayList<DownloadDirectory>();
				newdlds.add(dir);
				dir.parent = this;
				newdlds.addAll(downloadDirs);
				downloadDirs = newdlds;
			}
			fireTreeNodesInserted(new TreeModelEvent(DownloadQueue.this, QueueRoot.this.getPath(), new int[] {0}, new TreeNode[] {dir}));
		}
		
		/**
		 * Promotes the specified file to the top of the download queue.
		 * @param file
		 */
		void addFileFirst(DownloadFile file, File path) {
			// 1) Find/create the download directory for this file:
			GetDownloadDirectoryTask gddt = new GetDownloadDirectoryTask(path, true); // Must be first!
			Utilities.edispatch(gddt);
			DownloadDirectory dir = gddt.result;
			dir.addFileFirst(file);
		}
		
		/**
		 * Searches for an existing download directory with this path, if it doesn't exist then it is created empty and returned.
		 * @param path
		 * @return
		 */
		public DownloadDirectory getDownloadDirectory(File path) {
			GetDownloadDirectoryTask gddt = new GetDownloadDirectoryTask(path, false);
			Utilities.edispatch(gddt);
			return gddt.result;
		}
		
		/**
		 * This task must run in the Swing thread.
		 * @author gary
		 */
		private class GetDownloadDirectoryTask implements Runnable {
			
			File path;
			DownloadDirectory result;
			boolean mustBeFirst;
			
			/**
			 * Creates the download directory finding task.
			 * @param path The directory to download into
			 * @param mustBeFirst If true then the directory will be created in the queue _unless_ it already exists and is first.
			 */
			public GetDownloadDirectoryTask(File path, boolean mustBeFirst) {
				this.path = path;
				this.mustBeFirst = mustBeFirst;
			}
			
			@Override
			public void run() {
				int idx = -1;
				try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
					for (DownloadDirectory d : downloadDirs) {
						if (d.path.equals(path)) {
							result = d;
							return;
						}
						if (mustBeFirst) break;
					}
					// As we've not returned, we must need to create a new download directory:
					result = new DownloadDirectory(path, QueueRoot.this);
					if (mustBeFirst) {
						addDirectoryFirst(result); // Fires its own event
					} else {
						downloadDirs.add(result);
						idx = downloadDirs.size() - 1;
					}
					treeModified = true;
					saver.requestSave();
				}
				if (!mustBeFirst) {
					fireTreeNodesInserted(new TreeModelEvent(DownloadQueue.this, QueueRoot.this.getPath(), new int[] {idx}, new TreeNode[] {result}));
				}
			}
		}
		
		@Override
		public Enumeration<DownloadDirectory> children() {
			return new Util.EnumerationWrapper<DownloadDirectory>(new ArrayList<DownloadDirectory>(downloadDirs));
		}

		@Override
		public boolean getAllowsChildren() {
			return true;
		}

		@Override
		public TreeNode getChildAt(int childIndex) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				return downloadDirs.get(childIndex);
			}
		}

		@Override
		public int getChildCount() {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				return downloadDirs.size();
			}
		}

		@Override
		public int getIndex(TreeNode node) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				return downloadDirs.indexOf(node);
			}
		}

		@Override
		public boolean isLeaf() {
			return false;
		}

		@Override
		public String getName() {
			return "(hidden root of downloads)";
		}

		@Override
		void resetDispatchId(int newId) {
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().writeLock())) {
				for (DownloadDirectory d : downloadDirs) {
					d.resetDispatchId(newId);
				}
			}
		}
		
		@Override
		public File getFile() {
			throw new UnsupportedOperationException("The queue root does not have a location on disk.");
		}

		@Override
		public void _promote(DownloadItem notused) {
			throw new UnsupportedOperationException("The queue root cannot be promoted.");
		}
		
		@Override
		protected void destroyPathCaches() {
			super.destroyPathCaches();
			try (LockHolder lock = LockHolder.hold(getReadWriteLock().readLock())) {
				for (DownloadItem item : downloadDirs) item.destroyPathCaches();
			}
		}
	}
	
	// Members:
	QueueRoot root = new QueueRoot();
	AtomicInteger nextDispatchId = new AtomicInteger();
	
	// Transient members:
	transient List<TreeModelListener> listeners;
	transient SafeSaver saver;
	transient IndexNodeCommunicator comm;
	transient Collection<Integer> noSourceDispatches;
	
	/**
	 * Marks this file's dispatch ID as having no sources.
	 * This also issues updates to a GUI for every item that has changed to 'no sources'.
	 * 
	 * @param f
	 */
	void notifyNoSources(final DownloadFile f) {
		if (!(f.dispatchID instanceof Integer)) throw new IllegalArgumentException("A dispatched file has no dispatch ID!");
		if (noSourceDispatches.contains(f.dispatchID)) return; // Don't do anything if it's already no-sourced.
		noSourceDispatches.add(f.dispatchID);
		Iterable<DownloadItem> iter = new QueueIterable(new Filter<DownloadItem>() {
			@Override
			public boolean accept(DownloadItem item) {
				return ((item instanceof DownloadFile) && (((DownloadFile) item).dispatchID == f.dispatchID));
			}
		}, getUltimateDownloadDirectoryRoot(f).children.values());
		
		for (DownloadItem item : iter) {
			item.updateThis(); // Exceedingly inefficient! n^2 complexity for the number of children in a directory.
		}
	}
	
	private DownloadDirectory getUltimateDownloadDirectoryRoot(DownloadItem item) {
		while (item.parent != root) {
			item = item.parent;
		}
		return (DownloadDirectory) item;
	}
	
	/**
	 * Generates an iterator on the download queue that will return all the items that satisfy the filter supplied.
	 * This supports remove() (but remove should probably never be used!)
	 * 
	 * It depth-first searches the download queue.
	 */
	private class QueueIterable implements Iterable<DownloadItem>, Iterator<DownloadItem> {
		
		/** Keeps track of the directories we have recursed into. */
		private Deque<Iterator<? extends DownloadItem>> stack = new ArrayDeque<Iterator<? extends DownloadItem>>();
		private DownloadItem next;
		Util.Filter<DownloadItem> filter;
		
		/**
		 * Creates a new iterator over the whole download queue.
		 * @param filter Appear to only show items that meet this filter
		 * @param startAt a collection of download items to start this iterator.
		 */
		public QueueIterable(Util.Filter<DownloadItem> filter, Collection<? extends DownloadItem> startAt) {
			this.filter = filter;
			stack.add(startAt.iterator());
		}
		
		/**
		 * Clears empty iterators off the stack, and ensures that the next item to be read will meet the filter.
		 */
		private void ensureNext() {
			if (next != null) return; // Do nothing if we already have a next item prepared.
			while (!stack.isEmpty()) {
				while (next == null) {
					if (stack.getFirst().hasNext()) {
						next = stack.getFirst().next();
						if (next instanceof DownloadDirectory) stack.addFirst(((DownloadDirectory) next).children.values().iterator());
						if (filter.accept(next)) {
							return;
						} else {
							next = null;
						}
					} else {
						stack.removeFirst();
						break;
					}
				}
			}
		}
		
		@Override
		public void remove() {
			stack.getFirst().remove(); // Remove from the current iterator.
		}
		
		@Override
		public DownloadItem next() {
			ensureNext();
			DownloadItem copy = next;
			next = null;
			return copy;
		}
		
		@Override
		public boolean hasNext() {
			ensureNext();
			return next != null;
		}

		@Override
		public Iterator<DownloadItem> iterator() {
			return this;
		}
	}
	
	/**
	 * Submits a collection of filesystem entries to be downloaded into the default download directory.
	 * @param files A collection of file system entries to add, directories will be recursed into.
	 * @param intoDirectory may specify a directory within the default directory to add the files, or null otherwise
	 * @param listener a submission listener to be notified when a file is added to the queue, may be null.
	 */
	public void submitToDefault(Collection<FileSystemEntry> files, String intoDirectory,  DownloadSubmissionListener listener) {
		submit(dc.getDefaultDownloadDirectory(), files, intoDirectory, listener);
	}

	/**
	 * Submits a collection of filesystem entries to be downloaded into the download directory specified.
	 * @param toDirectory the directory to save the files into.
	 * @param files A collection of file system entries to add, directories will be recursed into.
	 * @param intoDirectory A directory to place the collection of files into in the toDirectory. Specify null to use the download directory directly.
	 * @param listener a submission listener to be notified when a file is added to the queue, may be null.
	 */
	public void submit(final File toDirectory, final Collection<FileSystemEntry> files, final String intoDirectory, final DownloadSubmissionListener listener) {
		Thread worker = new Thread(new Runnable() {
			@Override
			public void run() {
				DownloadDirectory todir = root.getDownloadDirectory(toDirectory);
				// Put everything into a directory if specified.
				if (intoDirectory != null) todir = todir.getChildDirectory(intoDirectory);
				// Grab a dispatch ID for this...
				Integer toUse = nextDispatchId.getAndIncrement();
				todir.submit(files, listener, toUse);
				fireSubmitCompleteEvent(listener);
			}
		}, "Download queuer for " + intoDirectory);
		worker.start();
	}
	
	/**
	 * Submits a listable entry (something that is certainly not a file) for downloading.
	 * @param toDirectory the directory to download into, null for default.
	 * @return
	 */
	public void submit(final File toDirectory, final ListableEntry entry, final String intoDirectory, final DownloadSubmissionListener listener) {
		// Strategy: Lookup the children of the entry then use the existing submit method.
		Thread lookupWorker = new Thread(new Runnable() {
			@Override
			public void run() {
				Collection<FileSystemEntry> children;
				if (entry instanceof FileSystemEntry) {
					children = comm.lookupChildren((FileSystemEntry) entry);
				} else {
					// Non-FSE ListableEntries will never need to load. (whoa assumptions)
					children = entry.getAllChildren();
				}
				
				if (toDirectory == null) {
					submitToDefault(children, intoDirectory, listener);
				} else {
					submit(toDirectory, children, intoDirectory, listener);
				}
			}
		}, "Download queue initial lookup worker for " + intoDirectory);
		lookupWorker.start();
	}
	
	public List<DownloadDirectory> getRootDownloadDirectories() {
		return root.downloadDirs;
	}
	
	private void fireSubmitCompleteEvent(final DownloadSubmissionListener listener) {
		try {
			Utilities.dispatch(new Runnable() {
				@Override
				public void run() {
					listener.complete();
				}
			}, false);
			
		} catch (Exception e) {
			Logger.warn("Couldn't dispatch submission complete event: " + e);
			Logger.log(e);
		}
	}

	/** Set to true whenever the tree is modified in a structural way. */
	transient volatile private boolean treeModified;
	
	transient volatile private int iterationIdx;
	
	/**
	 * Returns a download file from the queue that is:
	 * ) not active
	 * ) may have sources
	 * ) was not returned recently
	 * 
	 * This may return null if there are no such items, or if the end of the queue has been reached.
	 * (this means there is always one null return per iteration)
	 * 
	 * This will safely traul through the whole tree repeatedly even with concurrent modifications :o
	 * (however it may take arbitrarily long if the list is consistently and speedily being modified)
	 * @return
	 */
	DownloadFile getInactiveDownloadFile() {
		// Reset the iterator if the tree has been modified or there is no iterator already.
		if (treeModified || queueIterator == null) setupQueueIterator();
		
		// Return out of the infinite loop on definite success or definite failure.
		while (true) {
			try {
				if (queueIterator.hasNext() == false) {
					iterationIdx++; // Entirely new iteration! :o
					queueIterator = null; // Next invocation will recreate the iterator.
					return null; // The customary null to indicate no items/end of the list.
					
				} else {
					DownloadFile i = (DownloadFile) queueIterator.next(); // If the tree is modified this may not throw, so we must detect it too.
					if (treeModified) throw new ConcurrentModificationException(); // Hmmm... Well it does mean concurrent modification.
					i.lastIterationIdx = iterationIdx;
					return i;
				}
			} catch (ConcurrentModificationException e) {
				// The queue iterator is not happy. Can't blame it, so recreate the iterator and try again.
				setupQueueIterator();
			}
		}
	}
	
	/**
	 * Generates the iterator used to traverse the tree. This supplies the filtering that is used.
	 */
	private void setupQueueIterator() {
		queueIterator = new QueueIterable(new Filter<DownloadItem>() {
			@Override
			public boolean accept(DownloadItem item) {
				if (item instanceof DownloadFile) {
					DownloadFile f = (DownloadFile) item;
					if (f.active == null || f.active.worker == null // Not active.
					&& !noSourceDispatches.contains(f.dispatchID) // Might have sources.
					&& f.lastIterationIdx < iterationIdx) // Has not been used this iteration.
						return true;
				}
				return false;
			}
		}, root.downloadDirs);
		treeModified = false;
	}
	
	transient QueueIterable queueIterator;

	/**
	 * Removes all 'no sources' markers from the download queue, and notifies the GUI of the change.
	 */
	public void newPeersPresent() {
		final Set<Integer> wasNoSources = new HashSet<Integer>(noSourceDispatches);
		noSourceDispatches.clear();
		
		// Notify GUI:
		Iterable<DownloadItem> iter = new QueueIterable(new Filter<DownloadItem>() {
			@Override
			public boolean accept(DownloadItem item) {
				return item instanceof DownloadFile && wasNoSources.contains(((DownloadFile) item).dispatchID);
			}
		}, root.downloadDirs);
		
		for (DownloadItem item : iter) {
			item.updateThis(); // Exceedingly inefficient! n^2 complexity for the number of children in a directory.
		}
	}
	
	/**
	 * Recurses the tree to find how many bytes remain to be downloaded.
	 * @return
	 */
	public long calculateSize() {
		Iterable<DownloadItem> allFiles = new QueueIterable(new Filter<DownloadItem>() {
			@Override
			public boolean accept(DownloadItem item) {
				return item instanceof DownloadFile;
			}
		}, root.downloadDirs);
		
		long ret = 0;
		for (DownloadItem item : allFiles) {
			DownloadFile file = (DownloadFile) item;
			DownloadInfo info = file.active;
			// If the file's active then use the size-{sum of all bytes across all chunks}.
			// Active is copied to prevent races if it becomes null. (surprisingly common)
			ret += info != null ? info.bytesRemaining() : file.size;
		}
		return ret;
	}
	
	
	//====================
	//  Persistence:
	//====================

	private void setup() {
		listeners = new ArrayList<TreeModelListener>();
		saver = new SafeSaver(this, FS2Constants.CLIENT_DOWNLOADQUEUE_SAVE_MIN_INTERVAL);
		queueItemUpdater = new QueueItemUpdater();
		noSourceDispatches = Collections.synchronizedCollection(new HashSet<Integer>());
		iterationIdx = 1; // Indicates that all items in the tree have not yet been considered this cycle.
		
		dcls = new ArrayList<DownloadCompleteListener>();
	}

	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		setup();
	}

	transient DownloadController dc;
	
	private static final String queueFileName = "downloadqueue";
	
	public static DownloadQueue getDownloadQueue(IndexNodeCommunicator comm, DownloadController downloadController) {
		File queue = Platform.getPlatformFile(queueFileName);
		if (!queue.exists()) return new DownloadQueue(comm, downloadController);
		
		try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(queue)))) {
			DownloadQueue dq = (DownloadQueue) ois.readObject();
			dq.comm = comm;
			dq.dc = downloadController;
			return dq;
			
		} catch (Exception e) {
			Logger.warn("The download queue couldn't be loaded. Starting afresh...");
			Logger.log(e);
			return new DownloadQueue(comm, downloadController);
		}
	}
	
	public void shutdown() {
		saver.saveShutdown();
		Thread swc = saveWorker;
		if (swc != null) {
			try {
				swc.join();
			} catch (InterruptedException doNotCare) {}
		}
	}
	
	volatile transient Thread saveWorker;
	
	public synchronized void doSave() {
		if (saveWorker != null) return;
		saveWorker = new Thread(new Runnable() {
			@Override
			public void run() {
				File saveAs = Platform.getPlatformFile(queueFileName);
				try {
					Util.writeObjectToFile(DownloadQueue.this, saveAs);
					
				} catch (IOException e) {
					Logger.warn("Couldn't save download queue to a file. " + e);
					Logger.log(e);
					
				} finally {
					saveWorker = null;
				}
			}
		}, "Download Queue saver");
		saveWorker.setDaemon(false);
		saveWorker.start();
	}
	
	private DownloadQueue(IndexNodeCommunicator comm, DownloadController downloadController) {
		setup();
		this.comm = comm;
		this.dc = downloadController;
	}
	
	//==================================================
	// Facilities to allow this queue to drive a tree:
	//==================================================
	
	/**
	 * Will (with a rate-limit) update the GUI to reflect changes to the item specified.
	 */
	private void throttledTreeNodeChanged(DownloadItem i) {
		queueItemUpdater.addItem(i);
		Util.scheduleExecuteNeverFasterThan(FS2Constants.CLIENT_EVENT_MIN_INTERVAL, queueItemUpdater);
	}
	
	transient QueueItemUpdater queueItemUpdater;
	
	private class QueueItemUpdater implements Deferrable {
		
		private Set<DownloadItem> toUpdate = new HashSet<DownloadItem>();
		
		public void addItem(DownloadItem i) {
			synchronized (toUpdate) {
				toUpdate.add(i);
			}
		}
		
		@Override
		public void run() {
			final List<DownloadItem> copy;
			synchronized (toUpdate) {
				copy = new ArrayList<DownloadItem>(toUpdate);
				toUpdate.clear();
			}
			Utilities.edispatch(new Runnable() {
				@Override
				public void run() {
					synchronized (listeners) {
						for (TreeModelListener l : listeners) {
							for (DownloadItem i : copy) {
								try {
									DownloadItem parent = (DownloadItem) i.getParent();
									int indexInParent = parent.getIndex(i);
									if (indexInParent > -1) {
										l.treeNodesChanged(new TreeModelEvent(DownloadQueue.this, parent.getPath(), new int[] {indexInParent}, new Object[] {this}));
									}
								} catch (Exception ex) {
									Logger.log("Couldn't update download queue tree: " + ex);
									Logger.log(ex);
								}
							}
						}
					}
				}
			});
		}
	}
	
	/**
	 * Notifies {@link TreeModelListener}s that this filesystem has changed. Must be called from a Swing thread.
	 * @param e
	 */
	private void fireTreeNodesInserted(final TreeModelEvent e) {
		synchronized (listeners) {
			for (TreeModelListener l : listeners) {
				l.treeNodesInserted(e);
			}
		}
	}

	/**
	 * Notifies {@link TreeModelListener}s that this filesystem has changed. Must be called in the Swing thread.
	 * @param e
	 */
	private void fireTreeNodesRemoved(final TreeModelEvent e) {
		synchronized (listeners) {
			for (TreeModelListener l : listeners) {
				l.treeNodesRemoved(e);
			}
		}
	}
	
	
	@Override
	public void addTreeModelListener(TreeModelListener l) {
		synchronized (listeners) {
			listeners.add(l);
		}
	}

	@Override
	public Object getChild(Object parent, int index) {
		return ((TreeNode) parent).getChildAt(index);
	}

	@Override
	public int getChildCount(Object parent) {
		return ((TreeNode) parent).getChildCount();
	}

	@Override
	public int getIndexOfChild(Object parent, Object child) {
		return ((TreeNode) parent).getIndex((TreeNode) child);
	}

	@Override
	public Object getRoot() {
		return root;
	}

	@Override
	public boolean isLeaf(Object node) {
		return ((TreeNode) node).isLeaf();
	}

	@Override
	public void removeTreeModelListener(TreeModelListener l) {
		synchronized (listeners) {
			listeners.remove(l);
		}
	}

	@Override
	public void valueForPathChanged(TreePath path, Object newValue) {
		// Do nothing. The tree is not user-editable.
	}
	
	/** Download completion listener things. */
	private transient List<DownloadCompleteListener> dcls = new ArrayList<DownloadCompleteListener>();
	
	/**
	 * Registers a new listener for file-completion events.
	 * The events will not be triggered in a Swing thread!
	 * @param dcl
	 */
	public void addDownloadCompleteListener(DownloadCompleteListener dcl) {
		dcls.add(dcl);
	}
	
	private void fireDownloadCompleteEvent(DownloadFile file) {
		for (DownloadCompleteListener dcl : dcls) dcl.downloadComplete(file);
	}
	
}
