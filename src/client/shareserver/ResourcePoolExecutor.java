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
import java.util.function.Function;

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
	
	public interface ResourceAllocator<T> extends Function<T, ExecutorService> {
		
		default ExecutorService apply(T t) {
			return getAllocation(t);
		}
		
		/** Returns the ExcecutorService allocated to this resource. */
		ExecutorService getAllocation(T resource);
	}
	
	public static class DefaultAllocator<T> implements ResourceAllocator<T> {
		
		private final ThreadFactory factory;
		
		public DefaultAllocator(ThreadFactory factory) {
			this.factory = factory;
		}
		
		@Override
		public ExecutorService getAllocation(T resource) {
			return Executors.newFixedThreadPool(1, factory);
		}
	}
	
	protected final ConcurrentMap<T, ExecutorService> resourceMap = new ConcurrentHashMap<T, ExecutorService>();
	
	public ResourcePoolExecutor(Iterable<T> resources) {
		this(resources, Executors.defaultThreadFactory());
	}
	
	public ResourcePoolExecutor(Iterable<T> resources, ThreadFactory factory) {
		this(resources, new DefaultAllocator<T>(factory));
	}
	
	public ResourcePoolExecutor(Iterable<T> resources, ResourceAllocator<T> allocator) {
		for (T resource : resources) {
			resourceMap.computeIfAbsent(resource, allocator);
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
			ExecutorService service = resourceMap.get(resource);
			if (service != null) {
				service.execute(user);
				return;
			}
		}
		throw new RejectedExecutionException("Resource not found or unspecified");
	}
	
}
