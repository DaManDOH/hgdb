package org.hypergraphdb.cache;

import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.hypergraphdb.HGAtomCache;
import org.hypergraphdb.HGHandle;
import org.hypergraphdb.HGPersistentHandle;
import org.hypergraphdb.HyperGraph;
import org.hypergraphdb.event.HGAtomEvictEvent;
import org.hypergraphdb.handle.DefaultManagedLiveHandle;
import org.hypergraphdb.handle.HGLiveHandle;
import org.hypergraphdb.handle.HGManagedLiveHandle;
import org.hypergraphdb.handle.PhantomHandle;
import org.hypergraphdb.handle.PhantomManagedHandle;
import org.hypergraphdb.util.SoftHashMap;

/**
 * 
 * <p>
 * This cache implementation interacts with the Java garbage collector, by using
 * the <code>java.lang.ref</code> facilities, in order to implement its eviction 
 * policy. The eviction policy is the following: an atom is removed from the cache
 * if and only if either (1) its runtime Java instance is garbage collected or (2)
 * it is explicitly removed from the HyperGraph DB. Freezing an atom will keep it in
 * the cache even when it's garbage collected, but not if it's removed from DB altogether.  
 * </p>
 * 
 * <p>
 * The <code>WeakRefAtomCache</code> is a bit misleading: weak reference are in fact
 * not used at all. Soft references are used when caching incidence sets and phantom
 * references are used for atoms (@see org.hypergraphdb.handle.PhantomHandle).
 * </p>
 * 
 * @author Borislav Iordanov
 *
 */
public class WeakRefAtomCache implements HGAtomCache 
{	
	private HyperGraph graph = null;
	
	private SoftHashMap<HGPersistentHandle, HGHandle[]> incidenceCache = 
		new SoftHashMap<HGPersistentHandle, HGHandle[]>();
	
    private final HashMap<HGPersistentHandle, PhantomHandle> 
    	liveHandles = new HashMap<HGPersistentHandle, PhantomHandle>();
		
	private WeakHashMap<Object, PhantomHandle> atoms = 
		new WeakHashMap<Object, PhantomHandle>();
	
	private IdentityHashMap<HGLiveHandle, Object> frozenAtoms = 
		new IdentityHashMap<HGLiveHandle, Object>();
	
	public static final long DEFAULT_PHANTOM_QUEUE_POLL_INTERVAL = 500;
	
	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private ReentrantReadWriteLock incidenceLock = new ReentrantReadWriteLock();
	private ReferenceQueue refQueue = new ReferenceQueue();
	private PhantomCleanup phantomCleanupThread = new PhantomCleanup();
	private long phantomQueuePollInterval = DEFAULT_PHANTOM_QUEUE_POLL_INTERVAL;
	private boolean closing = false;
	
	//
	// This handle class is used to read atoms during closing of the cache. Because
	// closing the HyperGraph may involve a lot of cleanup activity where it's necessary
	// to read atoms (mostly types) into main memory just temporarily, we need some
	// way to enact this temporarility. 
	//
	private static class TempLiveHandle extends DefaultManagedLiveHandle
	{
		public TempLiveHandle(Object ref, HGPersistentHandle persistentHandle, byte flags)
		{
			super(ref, persistentHandle, flags, 0, 0);
		}
		
		public void setRef(Object ref)
		{
			this.ref = ref;
		}
	}	
	
	private void processRefQueue() throws InterruptedException
	{
		PhantomHandle ref = (PhantomHandle)refQueue.remove(phantomQueuePollInterval);
		while (ref != null)
		{
			graph.getEventManager().dispatch(graph, 
					 new HGAtomEvictEvent(ref, ref.fetchRef()));
			lock.writeLock().lock();
			try
			{
				liveHandles.remove(ref.getPersistentHandle());
			}
			finally
			{
				lock.writeLock().unlock();
			}
			ref.clear();
			synchronized (ref) { ref.notifyAll(); }
			ref = (PhantomHandle)refQueue.poll();
		}
	}
	
	private class PhantomCleanup extends Thread 
	{
		private boolean done;
		
	    public void run() 
	    {
			PhantomHandle.returnEnqueued.set(Boolean.TRUE);
	        for (done = false; !done; ) 
	        {
	        	try 
	            {
	        		processRefQueue();
	            } 
	        	catch (InterruptedException exc) 
	        	{
	                Thread.currentThread().interrupt();
	            }
	        }
			PhantomHandle.returnEnqueued.set(Boolean.FALSE);
	    }

	    public void end()
	    {
	    	this.done = true;
	    }
	}
	
	public WeakRefAtomCache()
	{
		phantomCleanupThread.start();
	}
	
	public void setHyperGraph(HyperGraph hg) 
	{
		this.graph = hg;
	}
	
	public HGLiveHandle atomRead(HGPersistentHandle pHandle, 
								 Object atom,
								 byte flags) 
	{
		if (closing)
			return new TempLiveHandle(atom, pHandle, flags);
		PhantomHandle h = new PhantomHandle(atom, pHandle, flags, refQueue);
		lock.writeLock().lock();
		try
		{
			atoms.put(atom, h);
			liveHandles.put(pHandle, h);
		}
		finally
		{
			lock.writeLock().unlock();
		}
		return h;
	}

	public HGManagedLiveHandle atomRead(HGPersistentHandle pHandle,
									    Object atom, 
									    byte flags, 
									    long retrievalCount, 
									    long lastAccessTime) 
	{
		if (closing)
			return new TempLiveHandle(atom, pHandle, flags); 
		PhantomManagedHandle h = new PhantomManagedHandle(atom, 
														  pHandle, 
														  flags, 
														  refQueue,
														  retrievalCount,
														  lastAccessTime);
		lock.writeLock().lock();
		try
		{
			atoms.put(atom, h);
			liveHandles.put(pHandle, h);
		}
		finally
		{
			lock.writeLock().unlock();
		}
		return h;
	}

	public void atomRefresh(HGLiveHandle handle, Object atom) 
	{
		if (closing)
		{
			if (handle instanceof PhantomHandle)
				((PhantomHandle)handle).storeRef(atom);
			else
				((TempLiveHandle)handle).setRef(atom);
			return;
		}
		if (handle == null)
			throw new NullPointerException("atomRefresh: handle is null.");
		
		lock.writeLock().lock();
		
		try
		{
			PhantomHandle ph = (PhantomHandle)handle;
			PhantomHandle existing = liveHandles.get(ph.getPersistentHandle());
			
			if (existing != ph)
			{
				if (existing != null)
				{
					liveHandles.remove(existing.getPersistentHandle());
					atoms.remove(existing.getRef());				
				}
				ph.storeRef(atom);
				liveHandles.put(ph.getPersistentHandle(), ph);
				atoms.put(atom, ph);
			}		
			else if (ph.getRef() != atom)
			{
				atoms.remove(ph.getRef());
				ph.storeRef(atom);
				atoms.put(atom, ph);
			}
		}
		finally
		{
			lock.writeLock().unlock();
		}
	}

	public void close() 
	{	
		closing = true;
		phantomCleanupThread.end();	
		while (phantomCleanupThread.isAlive() )
			try { phantomCleanupThread.join(); } catch (InterruptedException ex) { }
		for (Iterator<Map.Entry<HGPersistentHandle, PhantomHandle>> i = liveHandles.entrySet().iterator(); i.hasNext(); ) 
		{
			PhantomHandle h = i.next().getValue();
			Object x = h.getRef();
			if (!h.isEnqueued())
			{
				graph.getEventManager().dispatch(graph, new HGAtomEvictEvent(h, x));
//				atoms.remove(x);
//				i.remove();				
			}
		}
		try
		{
			// Finish up with all reference that are currently enqueued.
			PhantomHandle.returnEnqueued.set(Boolean.TRUE);
			processRefQueue(); 
			PhantomHandle.returnEnqueued.set(Boolean.FALSE);
		}
		catch (InterruptedException ex)
		{
			// nothing to do here really, perhaps report somewhere that we shouldn't be
			// interrupted during this important procedure?
		}		
		//
		// Some defensive programming: check that all maps are empty at this point.
		//
/*		if (!atoms.isEmpty())
			throw new HGException("BUG: could not cleanup the HyperGraph cache properly, 'atoms' map not empty.");
		if (!liveHandles.isEmpty())
			throw new HGException("BUG: could not cleanup the HyperGraph cache properly, 'liveHandles' map not empty."); */
		frozenAtoms.clear();		
		incidenceCache.clear();
		atoms.clear();
		liveHandles.clear();
	}

	public HGLiveHandle get(HGPersistentHandle pHandle) 
	{
		lock.readLock().lock();
		try
		{
			PhantomHandle h = liveHandles.get(pHandle);
			if (h != null)
				h.accessed();
			return h;
		}
		finally
		{
			lock.readLock().unlock();			
		}
	}

	public HGLiveHandle get(Object atom) 
	{
		lock.readLock().lock();
		try
		{
			return atoms.get(atom);
		}
		finally
		{
			lock.readLock().unlock();
		}
	}

	public HGHandle[] getIncidenceSet(HGPersistentHandle handle) 
	{
		incidenceLock.readLock().lock();
		try
		{
			return incidenceCache.get(handle);
		}
		finally
		{
			incidenceLock.readLock().unlock();
		}
	}

	public void incidenceSetRead(HGPersistentHandle handle,
								 HGHandle[] incidenceSet) 
	{
		incidenceLock.writeLock().lock();
		try
		{
			incidenceCache.put(handle, incidenceSet);
		}
		finally
		{
			incidenceLock.writeLock().unlock();
		}
	}

	public void remove(HGLiveHandle handle) 
	{
		lock.writeLock().lock();
		try
		{
			liveHandles.remove(handle.getPersistentHandle());
			atoms.remove(handle.getRef());
		}
		finally
		{
			lock.writeLock().unlock();
		}
	}

	public void removeIncidenceSet(HGPersistentHandle handle)
	{
		incidenceLock.writeLock().lock();
		try
		{
			incidenceCache.remove(handle);
		}
		finally
		{
			incidenceLock.writeLock().unlock();
		}		
	}
	
	public boolean isFrozen(HGLiveHandle handle) 
	{
		synchronized (frozenAtoms)
		{
			return frozenAtoms.containsKey(handle);
		}
	}

	public void freeze(HGLiveHandle handle) 
	{
		Object atom = handle.getRef();
		if (atom != null)
			synchronized (frozenAtoms)
			{
				frozenAtoms.put(handle, atom);
			}
	}	

	public void unfreeze(HGLiveHandle handle) 
	{
		synchronized (frozenAtoms)
		{		
			frozenAtoms.remove(handle);
		}
	}
}