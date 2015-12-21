package indexnode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import indexnode.IndexConfigDefaults.IK;
import common.Config;
import common.Logger;

/**
 * Manages advertisements on many interfaces and addresses.
 * Handles both active and prospective adverts.
 * 
 * @author gp
 */
public class IndexAdvertismentManager {

	List<IndexAdvertiser> advertisers = new ArrayList<IndexAdvertiser>();
	AdvertDataSource ads;
	
	/**
	 * 
	 * @param conf - The indexnode config with parameters useful for this manager.
	 * @throws UnknownHostException 
	 * @throws SocketException 
	 */
	public IndexAdvertismentManager(Config conf, AdvertDataSource ads) throws UnknownHostException, SocketException {
		this.ads = ads;
		
		String bindTo = conf.getString(IK.BIND_INTERFACE);
		String advertiseOn = conf.getString(IK.ADVERTISE_ADDRESS);
		
		// Create an advertiser for each address on each interface:
		startAdvertisers(bindTo, advertiseOn);
	}

	private void startAdvertisers(String bindTo, String aos) throws UnknownHostException, SocketException {
		InetAddress advertiseOn = null;
		
		if (aos != null && !aos.equals("all")) {
			advertiseOn = InetAddress.getByName(aos);
		}
		
		if (bindTo.equals("all")) {
			Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
			while (ifs.hasMoreElements()) {
				advertiseOnInterface(ifs.nextElement(), advertiseOn);
			}
			
		} else if (bindTo.equals("")) {
			Logger.warn("You must specify a bind-interface (or \"all\") in your configuration!\nExiting...");
			
		} else {
			advertiseOnInterface(NetworkInterface.getByName(bindTo), advertiseOn);
		}
	}
	
	private void advertiseOnInterface(NetworkInterface if0, InetAddress advertiseOn) throws SocketException {
		if (!if0.isUp()) return;
		Enumeration<InetAddress> addrs = if0.getInetAddresses();
		if (!addrs.hasMoreElements()) {
			Logger.warn("Not advertising on (" + if0.getName() + ") " + if0.getDisplayName() + ", it has no addresses.");
			return;
		}
		Logger.log("Advertising on (" + if0.getName() + ") " + if0.getDisplayName());
		while (addrs.hasMoreElements()) {
			InetSocketAddress addr = new InetSocketAddress(addrs.nextElement(), ads.getPort());
			if (advertiseOn == null || advertiseOn.equals(addr.getAddress())) {
				advertisers.add(new IndexAdvertiser(addr, ads));
			}
		}
	}
	
	public void shutdown() {
		for (IndexAdvertiser ia : advertisers) {
			ia.shutdown();
		}
	}
	
}
