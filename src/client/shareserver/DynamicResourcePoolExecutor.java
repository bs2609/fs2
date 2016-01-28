package client.shareserver;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class DynamicResourcePoolExecutor<T> extends ResourcePoolExecutor<T> {
	
	public interface ResourceProvider<T> extends Supplier<Iterable<T>> {
		
		default Iterable<T> get() {
			return pollResources();
		}
		
		/** Returns the currently available resources. */
		Iterable<T> pollResources();
	}
	
	private static final Runnable doNothing = () -> {};
	private final ScheduledExecutorService poolUpdater = Executors.newSingleThreadScheduledExecutor();
	private final Runnable updatePool;
	private long minDelay;
	private TimeUnit unit;
	private ScheduledFuture<?> waiting;
	
	public DynamicResourcePoolExecutor(ResourceProvider<T> provider, long minDelay, TimeUnit unit) {
		this(provider, Executors.defaultThreadFactory(), minDelay, unit);
	}
	
	public DynamicResourcePoolExecutor(ResourceProvider<T> provider, ThreadFactory factory, long minDelay, TimeUnit unit) {
		this(provider, new DefaultAllocator<T>(factory), minDelay, unit);
	}
		
	public DynamicResourcePoolExecutor(final ResourceProvider<T> provider, final ResourceAllocator<T> allocator, long minDelay, TimeUnit unit) {
		super(provider.pollResources(), allocator);
		updatePool = new Runnable() {
			@Override
			public void run() {
				Set<T> resources = new HashSet<T>();
				for (T resource : provider.pollResources()) {
					resources.add(resource);
					resourceMap.computeIfAbsent(resource, allocator);
				}
				for (T resource : resourceMap.keySet()) {
					if (!resources.contains(resource)) {
						resourceMap.remove(resource).shutdown();
					}
				}
			}
		};
		this.minDelay = minDelay;
		this.unit = unit;
		waiting = poolUpdater.schedule(doNothing, minDelay, unit);
	}
	
	@Override
	public void shutdown() {
		poolUpdater.shutdown();
		super.shutdown();
	}
	
	@Override
	public List<Runnable> shutdownNow() {
		poolUpdater.shutdownNow();
		return super.shutdownNow();
	}
	
	@Override
	public void execute(Runnable command) {
		refreshPool();
		super.execute(command);
	}
	
	public boolean refreshPool() {
		if (!waiting.isDone()) {
			return false;
		}
		poolUpdater.submit(updatePool);
		waiting = poolUpdater.schedule(doNothing, minDelay, unit);
		return true;
	}
	
	public long getMinUpdateDelay(TimeUnit outputUnit) {
		return outputUnit.convert(minDelay, unit);
	}
	
	public synchronized void setMinUpdateDelay(long newDelay, TimeUnit newUnit) {
		minDelay = newDelay;
		unit = newUnit;
		if (waiting.getDelay(unit) < minDelay) {
			waiting.cancel(false);
			refreshPool();
		}
	}
	
}
