package client.indexnode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;

import client.gui.Utilities;
import client.indexnode.downloadcontroller.DownloadSource;
import client.platform.Platform;

import common.FS2Constants;
import common.Logger;
import common.SafeSaver;
import common.SafeSaver.Savable;
import common.Util;
import common.Util.Deferrable;
import common.Util.FileSize;

/**
 * A replacement for the more basic stats collector.
 * 
 * This class provides statistics such as transfered files and bytes but also
 * retains information about transfers between us and peers. This is used to
 * make informed decisions later about which peers to download from.
 * (and because it looks cool!)
 * 
 * It's saved to disk by serialisation.
 * 
 * It also implements a table model so any GUIs can receive (rate-limited!) events on change.
 * 
 * @author gary
 */
public class PeerStatsCollector implements Serializable, TableModel, Savable {
	
	private static final long serialVersionUID = 1909020075940063080L;
	
	@SuppressWarnings("unused")
	private class PeerStats implements Serializable {
		private static final long serialVersionUID = -5035853080520439295L;
		//The number of bytes transfered with this peer:
		private long upBytes;
		private long downBytes;
		//the number of files: (up: us->them, down: them->us)
		private long upFiles;
		private long downFiles;
		//total time (in millis) spent on each of the up and down transfers to this peer:
		private long upTime;
		private long downTime;
		//records if we arbitrarily like this peers
		private boolean favourite;
		//Their alias. This is used as their identifier, and it is acknowledged that there is likely no authentication on the indexnode.
		private String alias;
		
		private PeerStats(String alias) {
			this.alias = alias;
		}
		
		/**
		 * Returns the all time average bytes per second from this peer.
		 * @return
		 */
		long getAllTimeAverageDLSpeed() {
			if ((downTime/1000)==0) return 0;
			return downBytes/(downTime/1000);
		}
		
		@Override
		public String toString() {
			return alias;
		}
		
		/**
		 * Records the timestamp of the last receiving of bytes from this peer.
		 * This is so that multiple connections to this peer simultaniously cannot register the same time interval more than once, or
		 * to put it another way, so that no more than wall-clock time can ever be registered.
		 */
		private transient long lastReceivedBytes; 
	}
	
	/**
	 * Links aliases to indices in the arraylist
	 */
	private HashMap<String, Integer> peerIndices = new HashMap<String, Integer>();
	/**
	 * The actual list of peers:
	 */
	private ArrayList<PeerStats> peers = new ArrayList<PeerStats>();
	
	//all time scores:
	private long totalUpBytes = 0;
	private long totalUpTime = 0;
	private long totalUpFiles = 0;
	private long totalDownBytes = 0;
	private long totalDownTime = 0;
	private long totalDownFiles = 0;
	
	//====================
	// Transient members:
	//====================
	
	private transient long totalUpAtLoad;
	private transient long totalDownAtLoad;
	private transient List<TableModelListener> modelListeners;
	private transient Class<?>[] columnClasses;
	private transient String[] columnNames;
	private transient SafeSaver saver;
	private transient Set<String> toUpdate;
	private transient UpdateTask updater;
	
	/**
	 * Records the number of currently active downloads from each user.
	 * This is used to make more educated decisions about which users to download from.
	 */
	private transient Map<String, AtomicInteger> dlUsed;
	
	/**
	 * Records the remote peers that we are currently remotely queued by.
	 * Again, used to make more educated download decisions when there are multiple sources.
	 */
	private transient Map<String, AtomicInteger> remoteQueued;
	
	/**
	 * A sorted list of peers from most attractive to download from to least attractive.
	 */
	private transient List<PeerStats> rankedPeers;
	/**
	 * A list of human-accessible rank integers. (ie: 1 for most attractive)
	 * The indices are the same as those for the peers list.
	 */
	private transient List<Integer> dlRank;
	private transient boolean dlHappened;
	
	public transient static final int ALIAS_IDX=0;
	public transient static final int FAVOURITE_IDX=1;
	private transient static final int DOWNLOADED_IDX=2;
	private transient static final int UPLOADED_IDX=3;
	private transient static final int ADLS_IDX=4;
	private transient static final int RQ_IDX=5;
	private transient static final int RANK_IDX=6;
	
	//====================
	//  Logic:
	//====================
	
	/**
	 * Is the peer specified by the alias provided one of our favourites?
	 */
	public boolean isFavourite(String peerAlias) {
		return peers.get(peerIndices.get(peerAlias)).favourite;
	}
	
	/**
	 * Notifies the collector that a download has started from the alias given.
	 */
	public synchronized void downloadStarted(String alias) {
		addPeerIfNeeded(alias);
		dlUsed.computeIfAbsent(alias, k -> new AtomicInteger()).incrementAndGet();
		regenerateRanks();
		notifyAndSave(alias);
	}
	
	/**
	 * Notify this collector that we have downloaded bytes from a peer
	 * @param alias
	 * @param bytes
	 * @param timeTaken
	 */
	public synchronized void receivedBytes(String alias, long bytes, long timeTaken) {
		addPeerIfNeeded(alias);
		PeerStats p = peers.get(peerIndices.get(alias));
		p.downBytes+=bytes;
		
		 //ensure that the caller is not trying to say it took more time than wall-clock time. This can happen by mistake if there are multiple simultanious connections.
		long allowedTimeTaken = Math.min(timeTaken, System.currentTimeMillis()-p.lastReceivedBytes);
		peers.get(peerIndices.get(alias)).downTime+=allowedTimeTaken;
		p.lastReceivedBytes = System.currentTimeMillis();
		
		totalDownBytes+=bytes;
		totalDownTime+=timeTaken;
		dlHappened = true;
		notifyAndSave(alias);
	}
	
	/**
	 * Notifies this collector that a download has finished from a peer.
	 * @param alias
	 */
	public synchronized void downloadComplete(String alias) {
		addPeerIfNeeded(alias);
		peers.get(peerIndices.get(alias)).downFiles++;
		totalDownFiles++;
		int dlCount = dlUsed.get(alias).decrementAndGet();
		if (dlCount == 0) {
			dlUsed.remove(alias);
		}
		dlHappened = true;
		regenerateRanks();
		notifyAndSave(alias);
	}
	
	/**
	 * Notifies this collector that we have been queued by a peer.
	 * This may be safely called many times for a single peer,
	 * but the opposite peerUnqueuedUs(string) should be called as many times too!
	 * @param alias
	 */
	public synchronized void peerQueuedUs(String alias) {
		addPeerIfNeeded(alias);
		remoteQueued.computeIfAbsent(alias, k -> new AtomicInteger()).incrementAndGet();
		regenerateRanks();
		notifyAndSave(alias);
	}
	
	/**
	 * Notifies this collector that we are no longer remotely queued by the peer specified.
	 * This could be for any reason (such as now downloading, or given up), but importantly we are no longer queued.
	 * @param alias
	 */
	public synchronized void peerUnqueuedUs(String alias) {
		addPeerIfNeeded(alias);
		int qCount = remoteQueued.get(alias).decrementAndGet();
		if (qCount == 0) {
			remoteQueued.remove(alias);
		}
		regenerateRanks();
		notifyAndSave(alias);
	}
	
	private synchronized void regenerateRanks() {
		dlRank.clear();
		rankedPeers.clear();
		rankedPeers.addAll(peers);
		DownloadAttractivenessComparator dac =  new DownloadAttractivenessComparator();
		Collections.sort(rankedPeers, dac);
		
		int lastRank = 0;
		PeerStats lastP = null;
		Integer[] tRanks = new Integer[peers.size()];
		for (PeerStats p : rankedPeers) {
			//If this peer has the same attractiveness as the last then it gets the same rank, otherwise the next highest:
			if (lastP==null || dac.compare(lastP, p)!=0) {
				lastRank++;
			}
			
			lastP = p;
			tRanks[peerIndices.get(p.alias)]=lastRank;
		}
		dlRank.addAll(Arrays.asList(tRanks));
	}
	
	private class DownloadAttractivenessComparator implements Comparator<PeerStats> {
		
		/**
		 * Peers that have remotely queued us are indistinguishably terrible,
		 * then a favourite peer is always preferred,
		 * then a peer is more attractive if it has fewer current downloads.
		 */
		@Override
		public int compare(PeerStats o1, PeerStats o2) {
			if (remoteQueued.containsKey(o1.alias) && !remoteQueued.containsKey(o2.alias)) return  1;
			if (remoteQueued.containsKey(o2.alias) && !remoteQueued.containsKey(o1.alias)) return -1;
			
			// Continue if they are equal...
			if (o1.favourite && !o2.favourite) return -1;
			if (o2.favourite && !o1.favourite) return  1;
			
			// By this point, remQ1 == remQ2 and fav1 == fav2, so do the complex check:
			// Current approach is just to prefer the client with the fewer current downloads: (this works pretty well)
			int o1cdls = dlUsed.containsKey(o1.alias) ? dlUsed.get(o1.alias).get() : 0;
			int o2cdls = dlUsed.containsKey(o2.alias) ? dlUsed.get(o2.alias).get() : 0;
			return Integer.compare(o1cdls, o2cdls);
		}
	}
	
	private static final Random generator = new Random();
	
	/**
	 * Given a set of download sources this will return the most promising client of the lot.
	 * @param candidates
	 * @return
	 */
	public synchronized DownloadSource getBestSource(Map<String, DownloadSource> candidates) {
		addPeersIfNeeded(candidates.keySet());
		
		//1) find the rank of the highest ranked peers that are eligable
		String peerOfHighStanding = null;
		for (PeerStats p : rankedPeers) {
			if (candidates.containsKey(p.alias)) {
				peerOfHighStanding = p.alias;
				break;
			}
		}
		int candidateRank = dlRank.get(peerIndices.get(peerOfHighStanding));
		
		//2) find all peers of that rank, that are eligable...
		List<PeerStats> similarlyGreat = new ArrayList<PeerStats>();
		for (int i=0; i<peers.size(); i++) {
			if (dlRank.get(i)==candidateRank && candidates.containsKey(peers.get(i).alias)) similarlyGreat.add(peers.get(i));
		}
		
		//3) select one at random.
		int chosenIndex = generator.nextInt(similarlyGreat.size());
		String aliasOfChosen = similarlyGreat.get(chosenIndex).alias;
		DownloadSource potential = candidates.get(aliasOfChosen); //now this is a neat line :D
		if (potential==null) {
			return null;
		}
		return potential;
	}
	
	/**
	 * Manually add a new peer if they don't already exist, and make them favourite.
	 * @param alias the alias of the user
	 */
	public synchronized void addPeer(String alias) {
		addPeerInternal(alias, true);
	}
	
	private void addPeersIfNeeded(Set<String> sources) {
		for (String s : sources) {
			addPeerIfNeeded(s);
		}
	}
	
	private void addPeerIfNeeded(String alias) {
		addPeerInternal(alias, false);
	}
	
	private synchronized void addPeerInternal(String alias, boolean favourite) {
		if (peerIndices.containsKey(alias)) return;
		PeerStats p = new PeerStats(alias);
		p.favourite = favourite;
		peers.add(p);
		int idx = peers.size()-1;
		peerIndices.put(alias, idx);
		regenerateRanks();
		notifyPeerAdded(idx);
		saver.requestSave();
	}
	
	/**
	 * Removes a peer from the list. Don't know why you'd want to..
	 */
	public synchronized void forgetPeer(String alias) {
		int idx = peerIndices.get(alias);
		peers.remove(idx);
		//regenerate the peer indices list as some indices have likely changed:
		peerIndices.clear();
		for (int i=0; i<peers.size(); i++) {
			peerIndices.put(peers.get(i).alias, i);
		}
		regenerateRanks();
		notifyPeerRemoved(idx);
		saver.requestSave();
	}
	
	/**
	 * Notify this collector that a file has started to upload to a peer.
	 * @param alias
	 */
	public synchronized void sendingFileStarted(String alias) {
		addPeerIfNeeded(alias);
	}
	
	/**
	 * Notify this collector that bytes have been sent to a peer.
	 * @param alias
	 * @param bytes
	 * @param timeTaken in milliseconds
	 */
	public synchronized void sentBytes(String alias, long bytes, long timeTaken) {
		addPeerIfNeeded(alias);
		peers.get(peerIndices.get(alias)).upBytes+=bytes;
		peers.get(peerIndices.get(alias)).upTime+=timeTaken;
		totalUpBytes+=bytes;
		totalUpTime+=timeTaken;
		notifyAndSave(alias);
	}
	
	/**
	 * Notify this collector that a file has finished uploading to a peer.
	 * @param alias
	 */
	public synchronized void sentFile(String alias) {
		addPeerIfNeeded(alias);
		peers.get(peerIndices.get(alias)).upFiles++;
		totalUpFiles++;
		notifyAndSave(alias);
	}
	
	private class UpdateTask implements Deferrable {
		@Override
		public void run() {
			if (dlHappened) regenerateRanks();
			for (String p : toUpdate) {
				if (peerIndices.containsKey(p)) notifyPeerChanged(peerIndices.get(p));
			}
			toUpdate.clear();
			dlHappened = false;
		}
	}
	
	/**
	 * Updates the UI if it hasn't been recently, and saves this object.
	 * @param alias
	 */
	private void notifyAndSave(String alias) {
		saver.requestSave();
		toUpdate.add(alias);
		Util.scheduleExecuteNeverFasterThan(FS2Constants.CLIENT_EVENT_MIN_INTERVAL, updater);
	}
	
	/**
	 * Returns the total bytes ever uploaded.
	 */
	public synchronized long getTotalUpBytes() {
		return totalUpBytes;
	}
	
	public long getTotalUpFiles() {
		return totalUpFiles;
	}
	
	public long getTotalDownFiles() {
		return totalDownFiles;
	}
	
	public double getAllTimeUpSpeed() {
		return (totalUpTime<1000 ? 0 : totalUpBytes/(totalUpTime/1000));
	}
	
	public double getAllTimeDownSpeed() {
		return (totalDownTime<1000 ? 0 : totalDownBytes/(totalDownTime/1000));
	}
	
	/**
	 * standard ratio, Up divided by down...
	 * @return
	 */
	public float getAllTimeRatio() {
		return (float)(totalDownBytes==0 ? 1 : (double)totalUpBytes/(double)totalDownBytes);
	}
	
	public float getSessionRatio() {
		return (float)(getDownBytesForSession()==0 ? 1 : (double)getUpBytesForSession()/(double)getDownBytesForSession());
	}
	
	/**
	 * Returns the total bytes ever downloaded.
	 */
	public synchronized long getTotalDownBytes() {
		return totalDownBytes;
	}
	
	/**
	 * The number of bytes uploaded this session only.
	 * @return
	 */
	public synchronized long getUpBytesForSession() {
		return getTotalUpBytes()-totalUpAtLoad;
	}
	
	/**
	 * The number of bytes downloaded this session only.
	 * @return
	 */
	public synchronized long getDownBytesForSession() {
		return getTotalDownBytes()-totalDownAtLoad;
	}
	
	//====================
	//  Persistence:
	//====================

	private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		setup();
	}

	//prevent direct instantiation.
	private PeerStatsCollector() {
		setup();
	}
	
	private void setup() {
		totalDownAtLoad = getTotalDownBytes();
		totalUpAtLoad = getTotalUpBytes();
		modelListeners = new ArrayList<TableModelListener>();
		columnClasses = new Class[] {String.class, Boolean.class, FileSize.class, FileSize.class, FileSize.class, String.class, Integer.class}; //alias, fav, down, up, dlspeed, remotely queued, rank
		columnNames = new String[] {"Alias", "Favourite", "Downloaded from", "Uploaded to", "Avg. Dl speed", "Queued Us","Rank"};
		saver = new SafeSaver(this, FS2Constants.CLIENT_PEERSTATS_SAVE_MIN_INTERVAL);
		toUpdate = new HashSet<String>();
		updater = new UpdateTask();
		dlUsed = new HashMap<String, AtomicInteger>();
		dlRank = new ArrayList<Integer>();
		rankedPeers = new ArrayList<PeerStats>();
		remoteQueued = new HashMap<String, AtomicInteger>();
		regenerateRanks();
	}
	
	private static final String statsFileName = "peerstats";
	
	// Manage persistence:
	public static PeerStatsCollector getPeerStatsCollector() {
		File stats = Platform.getPlatformFile(statsFileName);
		if (!stats.exists()) {
			return new PeerStatsCollector();
		}
		try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(stats)))) {
			return (PeerStatsCollector) ois.readObject();
			
		} catch (Exception e) {
			Logger.warn("Stored peer statistics couldn't be loaded. Starting afresh...");
			Logger.log(e);
			return new PeerStatsCollector();
		}
	}
	
	public void shutdown() {
		saver.saveShutdown();
	}
	
	public synchronized void doSave() {
		File saveAs = Platform.getPlatformFile(statsFileName);
		try {
			Util.writeObjectToFile(this, saveAs);
			
		} catch (IOException e) {
			Logger.warn("Couldn't save peer stats to a file. " + e);
			Logger.log(e);
		}
	}
	
	//==========================
	// Table model stuff:
	//==========================
	
	@Override
	public void addTableModelListener(TableModelListener l) {
		synchronized (modelListeners) {
			modelListeners.add(l);
		}
	}

	@Override
	public Class<?> getColumnClass(int columnIndex) {
		return columnClasses[columnIndex];
	}

	@Override
	public int getColumnCount() {
		return columnNames.length;
	}

	@Override
	public String getColumnName(int columnIndex) {
		return columnNames[columnIndex];
	}

	@Override
	public int getRowCount() {
		return peers.size();
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		PeerStats peer = peers.get(rowIndex);
		switch (columnIndex) {
		case ALIAS_IDX:
			return peer.alias;
		case FAVOURITE_IDX:
			return peer.favourite;
		case DOWNLOADED_IDX:
			return new FileSize(peer.downBytes);
		case UPLOADED_IDX:
			return new FileSize(peer.upBytes);
		case ADLS_IDX:
			return new FileSize(peer.getAllTimeAverageDLSpeed(),true);
		case RQ_IDX:
			return (remoteQueued.containsKey(peer.alias) ? "yes" : "no");
		case RANK_IDX:
			return dlRank.get(rowIndex);
		}
		return null;
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		return columnIndex==FAVOURITE_IDX;
	}

	@Override
	public void removeTableModelListener(TableModelListener l) {
		synchronized (modelListeners) {
			modelListeners.remove(l);
		}
	}

	@Override
	public synchronized void setValueAt(Object aValue, int rowIndex, int columnIndex) {
		if (columnIndex==FAVOURITE_IDX) {
			peers.get(rowIndex).favourite = (Boolean)aValue;
			//notifyPeerChanged(rowIndex);
			regenerateRanks();
			for (int i=0; i<peers.size(); i++) {
				notifyPeerChanged(i);
			}
			//Logger.log(Util.niceSize(peers.get(rowIndex).downBytes));
			//Logger.log(peers.get(rowIndex).downTime);
		}
		saver.requestSave();
	}
	
	void notifyPeerChanged(int rowidx) {
		fireTableChanged(new TableModelEvent(this, rowidx));
	}
	
	void notifyPeerAdded(int rowidx) {
		fireTableChanged(new TableModelEvent(this, rowidx, rowidx, TableModelEvent.ALL_COLUMNS, TableModelEvent.INSERT));
	}
	
	void notifyPeerRemoved(int rowidx) {
		fireTableChanged(new TableModelEvent(this, rowidx, rowidx, TableModelEvent.ALL_COLUMNS, TableModelEvent.DELETE));
	}
	
	void fireTableChanged(final TableModelEvent e) {
		try {
			Utilities.dispatch(new Runnable() {
				@Override
				public void run() {
					synchronized (modelListeners) {
						for (TableModelListener l : modelListeners) {
							l.tableChanged(e);
						}
					}
				}
			}, false);
		} catch (Exception ex) {
			Logger.warn("Couldn't notify peer stats table listeners: " + ex);
			Logger.log(ex);
		}
	}
	
}
