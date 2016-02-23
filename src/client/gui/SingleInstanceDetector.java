package client.gui;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Set;

import common.FS2Constants;
import common.Logger;
import common.Util;

/**
 * Allows FS2 to notify a potential second instance that the user wanted to start FS2.
 * 
 * This uses IPv4, on localhost, 127.0.0.1.
 * 
 * The intension is not to prevent multiple FS2 clients but to allow the natural behaviour that if the user
 * launches the jar twice the second time simply unhides the already executing FS2.
 * 
 * Without this the second (accidental) invocation would just crash anyway due to a bind exception.
 * 
 * @author gary
 */
public class SingleInstanceDetector {

	ServerSocket socket;
	Listener listener;
	
	private class Listener extends Thread {
		
		volatile boolean shutdown = false;
		
		public Listener() {
			setDaemon(true);
			setName("Single instance detector thread");
		}
		
		@Override
		public void run() {
			while (!shutdown) {
				try (Socket in = socket.accept()) {
					synchronized (interesteds) {
						for (InstanceNotifiable n : interesteds) {
							n.newInstanceStarted();
						}
					}
					
				} catch (SocketException e) {
					// ignore
					
				} catch (Exception e) {
					Logger.warn("Exception while listening for new instances: " + e);
					
				} finally {
					Util.sleep(FS2Constants.CLIENT_INSTANCE_DETECTION_INTERVAL);
				}
			}
		}
	}
	
	/**
	 * Construct this to create a listener. This will notify the GUI if a second instance tried to launch but failed due to a bind exception.
	 */
	public SingleInstanceDetector() {
		try {
			socket = new ServerSocket(FS2Constants.ADVERTISEMENT_DATAGRAM_PORT + 1, 0, InetAddress.getLoopbackAddress());
			listener = new Listener();
			listener.start();
			
		} catch (IOException e) {
			Logger.warn("FS2 is unable to detect if new instances are started by mistake! Check port " + (FS2Constants.ADVERTISEMENT_DATAGRAM_PORT + 1) + " is free: " + e);
		}
	}
	
	public interface InstanceNotifiable {
		
		void newInstanceStarted();
	}
	
	Set<InstanceNotifiable> interesteds = new HashSet<InstanceNotifiable>(4);
	
	public void addNotifiable(InstanceNotifiable n) {
		synchronized (interesteds) {
			interesteds.add(n);
		}
	}
	
	public void removeNotifiable(InstanceNotifiable n) {
		synchronized (interesteds) {
			interesteds.remove(n);
		}
	}
	
	public void shutdown() {
		try {
			listener.shutdown = true;
			socket.close();
			
		} catch (IOException e) {
			Logger.log("Shutting down SingleInstanceDetector: " + e);
			Logger.log(e);
		}
	}
	
	public static boolean notifyOtherInstance() {
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), FS2Constants.ADVERTISEMENT_DATAGRAM_PORT + 1)) {
			return true;
			
		} catch (Exception e) {
			Logger.warn("Unable to notify another instance: " + e);
			return false;
		}
	}
	
}
