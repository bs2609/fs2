package indexnode;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import common.FS2Constants;
import common.Logger;
import common.Util;

/**
 * Advertises this indexnode via UDP broadcast.
 * 
 * Current advert format:
 * {protocol-version-identifier-string}:{insecurePort}:{advertUID}
 * 
 * @author gary
 */
public class IndexAdvertiser extends Thread {
	
	private volatile boolean shutdown = false;
	private DatagramSocket socket;
	private InetSocketAddress sock;
	private AdvertDataSource ads;
	boolean warned = false;
	
	public IndexAdvertiser(InetSocketAddress sock, AdvertDataSource ads) throws SocketException {
		super();
		this.ads = ads;
		Logger.log("Advertising starts on: "+sock.getAddress().getHostAddress());
		this.sock = sock;
		setDaemon(true);
		socket = new DatagramSocket(sock);
		setName("advertiser");
		start();
	}
	
	@Override
	public void run() {
		try {
			while (!shutdown) {
				// If this is an active indexnode, send that advert:
				if (ads.isActive()) {
					String activeMessage = FS2Constants.FS2_PROTOCOL_VERSION+":"+Integer.toString(ads.getPort())+":"+Long.toString(ads.getAdvertUID());
					sendAdvert(activeMessage);
				}
				
				// If this is a potential (not mutually exclusive from active!) indexnode then send that message:
				if (ads.isProspectiveIndexnode()) {
					String prospectiveMessage = FS2Constants.FS2_PROTOCOL_VERSION+":autoindexnode:"+Long.toString(ads.getIndexValue())+":"+Long.toString(ads.getAdvertUID());
					sendAdvert(prospectiveMessage);
				}
				
				Util.sleep(FS2Constants.INDEXNODE_ADVERTISE_INTERVAL_MS);
			}
			
		} catch (Exception e) {
			Logger.warn("IndexAdvertiser: "+e.toString()+ ", No longer advertising on: "+sock.getAddress().getHostAddress());
			//e.printStackTrace();
		}
	}

	private void sendAdvert(String message) throws UnknownHostException {
		byte[] advert = message.getBytes(StandardCharsets.UTF_8);
		InetAddress address = (socket.getInetAddress() instanceof Inet6Address) ? InetAddress.getByName("ff02::1") : InetAddress.getByName("255.255.255.255");
		DatagramPacket packet = new DatagramPacket(advert, advert.length, address, FS2Constants.ADVERTISEMENT_DATAGRAM_PORT);
		try {
			socket.send(packet);
			
		} catch (IOException e) {
			if (!warned) {
				Logger.warn("Failed to send an advertisment on: "+sock.toString()+", Retrying silently now. Why? "+e.toString());
				warned = true;
			}
		}
	}
	
	public void shutdown() {
		socket.close();
		shutdown = true;
	}
	
}
