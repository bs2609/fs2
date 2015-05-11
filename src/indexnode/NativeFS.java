package indexnode;

import java.io.UnsupportedEncodingException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import common.FS2Constants;
import common.FileList.Item;
import common.HttpUtil;
import common.Logger;
import common.Util;
import common.Util.ByteArray;

import indexnode.IndexNode.Client;
import indexnode.IndexNode.Share;

/**
 * An implementation of an FS2 filesystem that uses native Java objects for storage and search.
 * TODO: Remove grand mutexes and replace with ReadWriteLocks. (mostly for indices)
 * @author Gary
 */
public class NativeFS implements Filesystem {

	/**
	 * An implementation of FilesystemEntry that does not involve SQL.
	 * It contains links to all its children and its parent.
	 * @author Gary
	 */
	public class NativeEntry implements FilesystemEntry {

		private Map<String, NativeEntry> children = Collections.emptyMap();
		private String name = "";
		private long size = 0L;
		private int linkCount = 2;
		private NativeEntry parent = null;
		private ByteArray hash = ByteArray.empty();
		private Share share = null;
		
		public NativeEntry(NativeEntry parent) {
			this.parent = parent;
		}
		
		@Override
		public synchronized void adjustLinkCount(int count) {
			linkCount += count;
		}

		@Override
		public synchronized void adjustSize(long size) {
			this.size += size;
		}

		@Override
		public synchronized FilesystemEntry createChildDirectory(String name, Share share) {
			return createChildEntry(name, ByteArray.empty(), 0, 2, share);
		}

		@Override
		// Does not update sizes or link counts!
		public synchronized FilesystemEntry createChildEntry(String name, ByteArray hash, long size, int links, Share share) {
			NativeEntry newChild = new NativeEntry(this);
			newChild.hash = hash;
			newChild.name = name;
			newChild.size = size;
			newChild.linkCount = links;
			newChild.share = share;
			
			synchronized (children) {
				if (children.equals(Collections.emptyMap())) {
					children = new HashMap<String, NativeEntry>();
				}
				children.put(name, newChild);
			}
			
			// Now update the indices for this filesystem:
			if (!newChild.isDirectory()) {
				addHashIndex(newChild);
				count.incrementAndGet();
			}
			addToNameIndex(newChild);
			// Child is now in the filesystem.
			
			return newChild;
		}

		@Override
		/**
		 * Erases this entry and recursively all descendant entries.
		 * Does not update link counts or sizes!
		 */
		public void erase() {
			synchronized (parent.children) {
				parent.children.remove(this.name);
			}
			removeFromNameIndex(this);
			if (isDirectory()) {
				NativeEntry[] childs = new NativeEntry[0];
				// Copy the list of children because they will remove themselves from our hashtable when they are erased.
				synchronized (children) {
					childs = children.values().toArray(childs);
				}
				for (NativeEntry child : childs) {
					child.erase();
				}
			} else {
				removeFromHashIndex(this);
				count.decrementAndGet();
			}
		}

		@Override
		public Collection<NativeEntry> getAlternatives() {
			synchronized (hashIndex) {
				return hashIndex.get(hash);
			}
		}

		@Override
		public Map<String, NativeEntry> getChildren() {
			return children;
		}

		@Override
		public ByteArray getHash() {
			return hash;
		}

		@Override
		public int getLinkCount() {
			return linkCount;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public FilesystemEntry getNamedChild(String name) {
			synchronized (children) {
				return children.get(name);
			}
		}

		@Override
		public String getOwnerAlias() {
			return share.getOwner().getAlias();
		}

		@Override
		public FilesystemEntry getParent() {
			return parent;
		}

		@Override
		public String getPath(boolean urlEncode, boolean includeOwner) throws UnsupportedEncodingException {
			Deque<String> pathBits = getPathBits(urlEncode);
			if (!includeOwner) pathBits.remove();
			return Util.join(pathBits.toArray(), "/");
		}
		
		/**
		 * Returns a collection of path elements for this entry.
		 * @param urlEncode
		 * @return
		 * @throws UnsupportedEncodingException 
		 */
		private Deque<String> getPathBits(boolean urlEncode) throws UnsupportedEncodingException {
			Deque<String> ret = new ArrayDeque<String>();
			FilesystemEntry currentEntry = this;
			while (!currentEntry.isRoot()) {
				ret.addFirst(urlEncode ? HttpUtil.urlEncode(currentEntry.getName()) : currentEntry.getName());
				currentEntry = currentEntry.getParent();
			}
			return ret;
		}

		@Override
		public Share getShare() {
			return share;
		}

		@Override
		public long getSize() {
			return size;
		}

		@Override
		public String getURL() {
			try {
				return "http://" + share.getOwner().getURLAddress() + "/shares/" + getPath(true, false);
				
			} catch (Exception e) {
				Logger.severe("Couldn't generate URL for file: " + e);
				Logger.log(e);
				return "";
			}
		}

		@Override
		public boolean isDirectory() {
			return hash.equals(ByteArray.empty());
		}

		@Override
		public boolean isRoot() {
			return parent == null;
		}

		@Override
		public void rename(String newName) {
			synchronized (parent.children) {
				parent.children.remove(name);
				parent.children.put(newName, this);
			}
			removeFromNameIndex(this);
			name = newName;
			addToNameIndex(this);
		}
	}

	/** Used to lookup files by hash quickly: */
	private final Map<ByteArray, Set<NativeEntry>> hashIndex = new HashMap<ByteArray, Set<NativeEntry>>();
	
	private void addHashIndex(NativeEntry entry) {
		// Initialise this hash entry if it doesn't have a set yet:
		synchronized (hashIndex) {
			Set<NativeEntry> set = hashIndex.get(entry.hash);
			if (set == null) {
				set = new HashSet<NativeEntry>(1);
				hashIndex.put(entry.hash, set);
			}
			set.add(entry);
		}
	}
	
	private void removeFromHashIndex(NativeEntry entry) {
		synchronized (hashIndex) {
			Set<NativeEntry> set = hashIndex.get(entry.hash);
			set.remove(entry);
			// Remove empty sets so that they may be garbage collected later:
			if (set.isEmpty()) hashIndex.remove(entry.hash);
		}
	}
	
	/** Maps keywords onto sets of entries. */
	private final Map<String, Set<NativeEntry>> nameIndex = new HashMap<String, Set<NativeEntry>>();
	
	private final Pattern keywordSplitter = Pattern.compile("\\p{Punct}|\\p{Blank}");
	
	/**
	 * Gets an array of keywords from a filename or a query.
	 * @param input
	 * @return
	 */
	private String[] getKeywords(String input) {
		return keywordSplitter.split(input.toLowerCase());
	}
	
	/**
	 * Adds the keywords expressed by the filename of this entry to the keyword index.
	 * @param entry
	 */
	private void addToNameIndex(NativeEntry entry) {
		synchronized (nameIndex) {
			for (String keyword : getKeywords(entry.getName())) {
				Set<NativeEntry> set = nameIndex.get(keyword);
				if (set == null) {
					set = new HashSet<NativeEntry>(1);
					nameIndex.put(keyword, set);
				}
				set.add(entry);
			}
		}
	}
	
	/**
	 * Removes this entry from the name index.
	 * @param entry
	 */
	private void removeFromNameIndex(NativeEntry entry) {
		synchronized (nameIndex) {
			for (String keyword : getKeywords(entry.getName())) {
				Set<NativeEntry> set = nameIndex.get(keyword);
				if (set != null) {
					set.remove(entry);
					if (set.isEmpty()) nameIndex.remove(keyword);
				}
			}
		}
	}
	
	private final NativeEntry root = new NativeEntry(null);
	private final AtomicInteger count = new AtomicInteger();
	
	@Override
	public int countFiles() {
		return count.get();
	}

	@Override
	public void delistShare(Share share) {
		FilesystemEntry clientRoot = share.getOwner().getFilesystemRoot();
		FilesystemEntry shareRoot = clientRoot.getNamedChild(share.getName());
		// Decrease the client's total sharesize:
		clientRoot.adjustSize(-shareRoot.getSize());
		clientRoot.adjustLinkCount(-1);
		root.adjustSize(-shareRoot.getSize());
		shareRoot.erase();
	}

	@Override
	public void deregisterClient(FilesystemEntry entry) {
		root.adjustSize(-entry.getSize());
		root.adjustLinkCount(-1);
		entry.erase();
	}

	class FilePopularityComparator implements Comparator<Set<NativeEntry>> {
		@Override
		public int compare(Set<NativeEntry> o2, Set<NativeEntry> o1) {
			return Integer.compare(o1.size(), o2.size());
		}
	}
	
	@Override
	/**
	 * Expensive! so don't do this often!
	 */
	public Collection<NativeEntry> getPopularFiles(int limit) {
		LinkedList<Set<NativeEntry>> fileList;
		synchronized (hashIndex) {
			fileList = new LinkedList<Set<NativeEntry>>(hashIndex.values());
		}
		Collections.sort(fileList, new FilePopularityComparator());
		List<NativeEntry> res = new ArrayList<NativeEntry>();
		while (limit --> 0 && !fileList.isEmpty()) {
			for (NativeEntry entry : fileList.pop()) {
				res.add(entry);
				break;
			}
		}
		return res;
	}

	@Override
	public FilesystemEntry getRootEntry() {
		return root;
	}

	@Override
	public void importShare(Element root, Share share) {
		FilesystemEntry shareRoot = share.getOwner().getFilesystemRoot().createChildDirectory(share.getName(), share);
		importXMLIntoFilesystem(root, shareRoot, share);
		share.getOwner().getFilesystemRoot().adjustLinkCount(1);
		share.getOwner().getFilesystemRoot().adjustSize(shareRoot.getSize());
		this.root.adjustSize(shareRoot.getSize());
	}

	@Override
	public void importShare(Item root, Share share) {
		FilesystemEntry shareRoot = share.getOwner().getFilesystemRoot().createChildDirectory(share.getName(), share);
		importFileListIntoFilesystem(root, shareRoot, share);
		share.getOwner().getFilesystemRoot().adjustLinkCount(1);
		share.getOwner().getFilesystemRoot().adjustSize(shareRoot.getSize());
		this.root.adjustSize(shareRoot.getSize());
	}
	
	/**
	 * Returns the total filesize of all items it contains.
	 * @param onItem
	 * @param fsItem
	 * @return
	 */
	private long importFileListIntoFilesystem(Item onItem, FilesystemEntry fsItem, Share share) {
		int linksAcc = 0;
		long sizeAcc = 0L;
		
		for (Item childItem : onItem.children.values()) {
			if (childItem.isDirectory()) {
				sizeAcc += importFileListIntoFilesystem(childItem, fsItem.createChildDirectory(childItem.name, share), share);
				linksAcc += 1;
			} else {
				if (childItem.hashVersion != FS2Constants.FILE_DIGEST_VERSION_INT) continue;
				if (childItem.hash == null || childItem.hash.get().length != FS2Constants.FILE_DIGEST_BITS / 8) continue;
				sizeAcc += childItem.size;
				fsItem.createChildEntry(childItem.name, childItem.hash, childItem.size, 1, share);
			}
		}
		
		fsItem.adjustLinkCount(linksAcc);
		fsItem.adjustSize(sizeAcc);
		return sizeAcc;
	}
	
	/**
	 * Returns the total filesize of all items it contains.
	 * @param xmlItem
	 * @param fsItem
	 * @return
	 */
	private long importXMLIntoFilesystem(Element xmlItem, FilesystemEntry fsItem, Share share) {
		int linksAcc = 0;
		long sizeAcc = 0L;
		
		Node onNode = xmlItem.getFirstChild();
		while (onNode != null) {
			try {
				if (onNode.getNodeType() == Element.ELEMENT_NODE) {
					Element onElement = (Element) onNode;
					if (onElement.getTagName() == "directory") {
						sizeAcc += importXMLIntoFilesystem(onElement, fsItem.createChildDirectory(onElement.getAttribute("name"), share), share);
						linksAcc += 1;
					} else if (onElement.getTagName() == "file") {
						if (!onElement.getAttribute("hash-version").equals(FS2Constants.FILE_DIGEST_VERSION_XML)) continue;
						if (onElement.getAttribute("hash").length() != FS2Constants.FILE_DIGEST_BITS / 4) continue;
						long thisFileSize = Long.parseLong(onElement.getAttribute("size"));
						ByteArray hash = new ByteArray(Util.bytesFromHexString(onElement.getAttribute("hash")));
						sizeAcc += thisFileSize;
						fsItem.createChildEntry(onElement.getAttribute("name"), hash, thisFileSize, 1, share);
					}
				}
			} finally {
				onNode = onNode.getNextSibling();
			}
		}
		
		fsItem.adjustLinkCount(linksAcc);
		fsItem.adjustSize(sizeAcc);
		return sizeAcc;
	}
	
	@Override
	public FilesystemEntry lookupFromPath(String path) {
		String[] splitString = path.split("/");
		FilesystemEntry ret = root;
		
		int onIndex = 0;
		while (onIndex < splitString.length) {
			try {
				if (splitString[onIndex].equals("")) {
					continue;
				}
				ret = ret.getNamedChild(splitString[onIndex]);
				// If any path element is not found then just return "not found".
				if (ret == null) return null;
			} finally {
				onIndex++;
			}
		}
		
		return ret;
	}

	@Override
	public FilesystemEntry registerClient(Client client) {
		root.adjustLinkCount(1);
		return root.createChildDirectory(client.getAlias(), null);
	}

	@Override
	public Collection<NativeEntry> searchForHash(ByteArray hash) {
		Collection<NativeEntry> res;
		synchronized (hashIndex) {
			res = hashIndex.get(hash);
		}
		if (res == null) res = Collections.emptyList();
		return res;
	}

	@Override
	public Collection<NativeEntry> searchForName(String query) {
		Set<NativeEntry> results = new HashSet<NativeEntry>(0);
		boolean firstKeyword = true;
		for (String keyword : getKeywords(query)) {
			Set<NativeEntry> itemResults;
			synchronized (nameIndex) {
				itemResults = nameIndex.get(keyword);
			}
			// Null indicates this keyword matched nothing at all.
			if (itemResults == null) {
				results.clear();
				return results;
			}
			if (firstKeyword) {
				results = new HashSet<NativeEntry>(itemResults);
				firstKeyword = false;
			} else {
				results.retainAll(itemResults);
			}
		}
		return results;
	}

	@Override
	public long totalSize() {
		return root.getSize();
	}

	@Override
	public int countUniqueFiles() {
		synchronized (hashIndex) {
			return hashIndex.size();
		}
	}

	@Override
	// Pretty expensive really!
	public long uniqueSize() {
		long ret = 0;
		synchronized (hashIndex) {
			for (Set<NativeEntry> entries : hashIndex.values()) {
				for (NativeEntry file : entries) {
					ret += file.getSize();
					break; // We only want a single item from this set.
				}
			}
		}
		return ret;
	}

	private final AtomicLong estimatedTransfer = new AtomicLong();
	
	@Override
	public long getEstimatedTransfer() {
		return estimatedTransfer.get();
	}

	@Override
	public void incrementSent(long addSize) {
		estimatedTransfer.addAndGet(addSize);
	}

}
