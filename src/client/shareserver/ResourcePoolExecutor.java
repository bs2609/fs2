package client.shareserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 
 * 
 * 
 * @param <T> The type of the resource that this manages.
 */
public class ResourcePoolExecutor<T> extends AbstractExecutorService {
	
	public interface ResourceUser<T> extends Runnable {
		
		/** Returns the resource this task will be using. */
		T getResource();
	}
	
	private final ConcurrentMap<T, ExecutorService> resourceMap = new ConcurrentHashMap<T, ExecutorService>();
	
	public ResourcePoolExecutor(Iterable<T> resources) {
		for (T resource : resources) {
			resourceMap.put(resource, Executors.newFixedThreadPool(1));
		}
	}
	
	public ResourcePoolExecutor(Iterable<T> resources, ThreadFactory factory) {
		for (T resource : resources) {
			resourceMap.put(resource, Executors.newFixedThreadPool(1, factory));
		}
	}
	
	@Override
	public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
		ExecutorService pool = Executors.newCachedThreadPool();
		for (final ExecutorService ex : resourceMap.values()) {
			pool.submit(new Callable<Boolean>() {
				@Override
				public Boolean call() throws InterruptedException {
					return ex.awaitTermination(timeout, unit);
				}
			});
		}
		pool.shutdown();
		return pool.awaitTermination(timeout, unit);
	}
	
	@Override
	public boolean isShutdown() {
		for (ExecutorService ex : resourceMap.values()) {
			if (!ex.isShutdown()) return false;
		}
		return true;
	}
	
	@Override
	public boolean isTerminated() {
		for (ExecutorService ex : resourceMap.values()) {
			if (!ex.isTerminated()) return false;
		}
		return true;
	}
	
	@Override
	public void shutdown() {
		for (ExecutorService ex : resourceMap.values()) {
			ex.shutdown();
		}
	}
	
	@Override
	public List<Runnable> shutdownNow() {
		List<Runnable> list = new ArrayList<Runnable>();
		for (ExecutorService ex : resourceMap.values()) {
			list.addAll(ex.shutdownNow());
		}
		return list;
	}
	
	@Override
	public void execute(Runnable command) {
		if (command instanceof ResourceUser<?>) {
			ResourceUser<?> user = (ResourceUser<?>) command;
			Object resource = user.getResource();
			if (resourceMap.containsKey(resource)) {
				resourceMap.get(resource).execute(user);
				return;
			}
		}
		throw new RejectedExecutionException("Resource not found or undefined");
	}
}
