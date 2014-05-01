package client.indexnode.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import common.FS2Constants;
import common.Util;
import common.Util.Deferrable;

/**
 * Given adverts from other autoindexnode capable clients this class will store them to allow the current best autoindexnode to be determined.
 * 
 * @author gp
 *
 */
public class CapabilityRecorder {

	private class CapabilityRecord implements Comparable<CapabilityRecord> {
		Long capability;
		final long advertuid;
		long lastReceived;
		
		public CapabilityRecord(long advertuid, long capability) {
			this.advertuid = advertuid;
			this.capability = capability;
			this.lastReceived = System.currentTimeMillis();
		}
		
		public void update(long newCapability) {
			this.lastReceived = System.currentTimeMillis();
			this.capability = newCapability;
		}
		
		/**
		 * Determines if this record has expired.
		 * It is expired if it is older than five advertisment durations.
		 * @return
		 */
		public boolean isExpired() {
			return lastReceived < (System.currentTimeMillis()-FS2Constants.INDEXNODE_CAPABILITY_TABLE_DECAY);
		}
		
		@Override
		public String toString() {
			return "[c:"+capability+"]";
		}

		@Override
		public int compareTo(CapabilityRecord o) {
			return o.capability.compareTo(capability);
		}
	}
	
	// ArrayList for fast iteration over a small number of records. Infrequent insertion and deletion.s
	private List<CapabilityRecord> records = new ArrayList<CapabilityRecord>(50);
	private CapabilityRecord us;
	private final long ourAUID;
	
	public CapabilityRecorder(long advertuid) {
		ourAUID = advertuid;
	}
	
	/**
	 * Returns the number of auto-indexnode capable clients visible to this client
	 * @return
	 */
	public synchronized int getRecordCount() {
		return records.size();
	}
	
	/**
	 * Returns this client's capability rank
	 * returns 0 if we have no rank.
	 * @return
	 */
	public synchronized int getRank() {
		Collections.sort(records);
		return records.indexOf(us)+1;
	}
	
	/**
	 * Records a capability claim from another client.
	 * @param capability
	 * @param advertuid
	 */
	public synchronized void recordCapability(long capability, long advertuid) {
		try {
			for (CapabilityRecord cr : records) {
				if (cr.advertuid==advertuid) {
					cr.update(capability);
					return;
				}
			}
			//there wasn't a record for this advertuid, so create one
			records.add(new CapabilityRecord(advertuid, capability));
		} finally { 
			Util.executeNeverFasterThan(FS2Constants.INDEXNODE_ADVERTISE_INTERVAL_MS, recordScanner);
		}
	}
	
	private class RescanRecords implements Deferrable {
		@Override
		public void run() {
			winnerCapability = 0;
			Iterator<CapabilityRecord> cri = records.iterator();
			while (cri.hasNext()) {
				CapabilityRecord cr = cri.next();
				if (cr.isExpired()) {
					cri.remove(); 
					continue;
				}
				if (cr.advertuid==ourAUID) us = cr;
				if (cr.capability>winnerCapability) {
					winnerAdvertUID = cr.advertuid;
					winnerCapability = cr.capability;
				}
			}
		}
	}
	
	private RescanRecords recordScanner = new RescanRecords();
	
	/**
	 * Returns the capabilitys belonging to the client that has provided the greatest capability claim.
	 * @return
	 */
	public synchronized long getGreatestRecentCapability() {
		Util.executeNeverFasterThan(FS2Constants.INDEXNODE_ADVERTISE_INTERVAL_MS, recordScanner);
		return winnerCapability;
	}
	
	/**
	 * Returns the advertuid of the currently most capable potential indexnode.
	 * @return
	 */
	public synchronized long getWinnerAUID() {
		Util.executeNeverFasterThan(FS2Constants.INDEXNODE_ADVERTISE_INTERVAL_MS, recordScanner);
		return winnerAdvertUID;
	}
	
	/**
	 * Returns true if our capability is greater than any other we've heard of, or our UID is at the top of the table.s
	 * @param myCapability
	 * @param myAUID
	 * @return
	 */
	public synchronized boolean amIMostCapable(long myCapability) {
		return myCapability>getGreatestRecentCapability() || ourAUID==getWinnerAUID();
	}
	
	private long winnerAdvertUID = 0;
	private long winnerCapability = 0;
}
