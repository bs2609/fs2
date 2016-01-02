package indexnode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import common.FS2Constants;
import common.HttpUtil;
import common.Logger;
import common.Util;
import common.httpserver.HttpExchange;
import common.httpserver.HttpHandler;

import indexnode.IndexNode.Client;
import indexnode.IndexNode.Share;

/**
 * A fugly hacked together statistics page for the indexnode.
 * It's extremely expensive to generate so should not be generated often!
 * @author gp
 */
public class IndexStatistics implements HttpHandler {
	
	private final IndexNode onNode;
	private volatile long lastGenerated = 0L;
	private volatile String cachedStatsPage = "";
	private Document doc;
	private Element body;
	private IndexTemplate template;
	private volatile boolean generating = false;
	private final Object genMutex = new Object();
	
	public IndexStatistics(IndexNode indexNode) {
		onNode = indexNode;
		generating = true;
		generateStatistics();
	}
	
	public void handle(HttpExchange exchange) throws IOException {
		synchronized (genMutex) {
			if (System.currentTimeMillis() - lastGenerated > FS2Constants.INDEXNODE_CACHE_STATISTICS_DURATION && !generating) {
				generating = true;
				generateStatistics();
			}
		}
		synchronized (cachedStatsPage) {
			HttpUtil.simpleResponse(exchange, cachedStatsPage, 200);
		}
	}
	
	public void generateStatistics() {
		Thread worker = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					template = new IndexTemplate();
					template.setTitle("FS2 statistics");
					doc = template.doc;
					body = template.body;
					Element heading = doc.createElement("h3");
					heading.setTextContent("Statistics");
					template.getHeaderElement().appendChild(heading);
					Element general = addSection("General", "general");
					Element memory = addSection("Memory Usage", "memory");
					// Uptime:
					Date startDate = IndexStatistics.this.onNode.startedDate;
					addStatistic("Indexnode started", Util.formatDate(startDate), Long.toString(startDate.getTime()), "indexnode-started", general);
					// Memory usage:
					Runtime rt = Runtime.getRuntime();
					addStatistic("Maximum heap", Util.niceSize(rt.maxMemory()), Long.toString(rt.maxMemory()), "indexnode-maxheap", memory);
					addStatistic("Used heap", Util.niceSize(rt.totalMemory()), Long.toString(rt.totalMemory()), "indexnode-usedheap", memory);
					addStatistic("Available heap", Util.niceSize(rt.freeMemory()), Long.toString(rt.freeMemory()), "indexnode-freeheap", memory);
					// Number of files:
					int fileCount = IndexStatistics.this.onNode.fs.countFiles();
					addStatistic("Indexed files", Long.toString(fileCount), Long.toString(fileCount), "file-count", general);
					// Number of unique files:
					int uniqueCount = IndexStatistics.this.onNode.fs.countUniqueFiles();
					addStatistic("Unique files", Long.toString(uniqueCount), Long.toString(uniqueCount), "unique-file-count", general);
					// Total size:
					long totalSize = IndexStatistics.this.onNode.fs.totalSize();
					addStatistic("Total size", Util.niceSize(totalSize), Long.toString(totalSize), "total-size", general);
					// Total unique size:
					long uniqueSize = IndexStatistics.this.onNode.fs.uniqueSize();
					addStatistic("Size of unique files", Util.niceSize(uniqueSize), Long.toString(uniqueSize), "total-unique-size", general);
					// Total estimated transfer:
					long totalTransfer = IndexStatistics.this.onNode.fs.getEstimatedTransfer();
					addStatistic("Total bytes for all requested files", Util.niceSize(totalTransfer), Long.toString(totalTransfer), "total-transfer", general);
					// Number of clients:
					int numClients = IndexStatistics.this.onNode.clients.size();
					addStatistic("Connected clients", Integer.toString(numClients), Integer.toString(numClients), "client-count", general);
					// Clients by sharesize:
					clientSizes(addSection("Clients", "clients"));
					// Top ten popular files:
					template.generateFilelist(IndexStatistics.this.onNode.fs.getPopularFiles(100), false, true, addSection("Most popular files", "popular-files"));
					synchronized (cachedStatsPage) {
						cachedStatsPage = template.toString();
					}
					lastGenerated = System.currentTimeMillis();
					
				} catch (Exception e) {
					Logger.log(e);
					
				} finally {
					synchronized (genMutex) {
						generating = false;
					}
				}
			}
		});
		worker.setName("Statistics generation");
		worker.setDaemon(true);
		worker.setPriority(Thread.NORM_PRIORITY - 1);
		worker.start();
	}
	
	public long getTotalClientSize(Client c) {
		long ret = 0L;
		for (Share share : c.shares.values()) {
			ret += share.getSize();
		}
		return ret;
	}
	
	/** A comparator for descending sort of client's total share size. */
	class ClientComparator implements Comparator<Client> {
		@Override
		public int compare(Client o2, Client o1) {
			long o1s = getTotalClientSize(o1);
			long o2s = getTotalClientSize(o2);
			return Long.compare(o1s, o2s);
		}
	}
	
	private List<Client> sortedClientsBySize() {
		List<Client> ret = new ArrayList<Client>(onNode.clients.values());
		Collections.sort(ret, new ClientComparator());
		return ret;
	}
	
	private void clientSizes(Element section) {
		int rank = 1;
		for (Client client : sortedClientsBySize()) {
			addClientStatistic(Integer.toString(rank++) + ") " + client.getAlias(), Util.niceSize(getTotalClientSize(client)), Long.toString(getTotalClientSize(client)), "client-" + client.getAlias() + "-size", section, client.getAlias(), client.getAvatarHash());
		}
	}
	
	private void addClientStatistic(String name, String content, String machineReadableValue, String id, Element section, String alias, String avatarhash) {
		if (avatarhash != null && !avatarhash.equals("")) {
			Element img = doc.createElement("img");
			img.setAttribute("src", "/avatars/" + avatarhash + ".png");
			section.appendChild(img);
		}
		Element b = doc.createElement("b");
		b.setTextContent(name + ": ");
		section.appendChild(b);
		Element c = doc.createElement("span");
		c.setTextContent(content);
		c.setAttribute("id", id);
		c.setAttribute("value", machineReadableValue);
		if (avatarhash != null) c.setAttribute("fs2-avatarhash", avatarhash);
		if (alias != null) c.setAttribute("fs2-clientalias", alias);
		section.appendChild(c);
		section.appendChild(doc.createElement("br"));
	}
	
	private void addStatistic(String name, String content, String machineReadableValue, String id, Element section) {
		addClientStatistic(name, content, machineReadableValue, id, section, null, null);
	}
	
	private Element addSection(String name, String id) {
		body.appendChild(doc.createElement("hr"));
		Element heading = doc.createElement("h4");
		heading.setTextContent(name);
		body.appendChild(heading);
		Element newSection = doc.createElement("div");
		body.appendChild(newSection);
		newSection.setAttribute("id", id);
		return newSection;
	}
}