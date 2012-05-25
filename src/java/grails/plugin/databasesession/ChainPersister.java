package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.google.common.util.concurrent.MoreExecutors;

/**
 * A {@link Persister} that attempts a series of persistance strategies in order. 
 *
 * @author Robert Fischer
 */
public class ChainPersister {

	private final Logger log = Logger.getLogger(getClass());

	private List<Persister> persisters = new CopyOnWriteArrayList();

	/**
	* The executor that will be used to get some concurrency out of this chain. 
	* If you want to execute things serially (no concurrency), use {@link MoreExecutors#sameThreadExecutor()}.
	*/
	private volatile ExecutorService executor = Executors.newCachedThreadPool();

	// long store/get operations are guarantied to be atomic on volatile fields
	private volatile int secondsToWait = 20;

	/**
	* Sets the maximum number of seconds to wait on concurrent tasks. Set to {@code 0}  to wait forever.
	* See {@link #getSecondsToWait()} for details on negative values.
	*/
	public void setSecondsToWait(int seconds) {
		secondsToWait = seconds;
	}

	/**
	* Gets the maximum number of seconds to wait on concurrent tasks. If the set value is negative, it is coerced to
	* {@code 0}, which means to wait forever.
	*/
	public int getSecondsToWait() {
		return Math.max(0, secondsToWait);
	}

	/**
	* Sets the executor for concurrent persisting and fetching. 
	*/
	public void setExecutor(ExecutorService service) {
		if(service == null) throw new NullPointerException("Cannot assign a null executor");
		this.executor = service;
	}

	/**
	* Gets the executor for concurrent persisting and fetching.
	*/
	public ExecutorService getExecutor() {
		return executor;	
	}

	public List<Persister> getPersisters() {
		return new ArrayList<Persister>(persisters);
	}

	public void setPersisters(List<Persister> persisters) {
		if(persisters == null) throw new IllegalArgumentException("Cannot assign a null persisters property");
		this.persisters = new CopyOnWriteArrayList<Persister>(persisters);
	}

	/**
	* Adds a persister to the chain.
	*/
	public void addPersister(Persister p) {
		if(p == null) throw new IllegalArgumentException("Cannot add a null persister");
		persisters.add(p);
	}

	/**
	* Adds a persister to the chain at a particular index. 
	*/
	public void addPersister(Persister p, int index) {
		if(p == null) throw new IllegalArgumentException("Cannot add a null persister");
		persisters.add(index, p);
	}

	/**
	* Persists a session to each of the underlying {@link Persister}s. The sessionData may be {@code null}.
	* The persistance is done concurrently using the executor.
	*/
	public void persistSession(final SessionData sessionData) {
		for(final Persister p : persisters) {
			executor.submit(new Runnable() {
				public void run() {
					p.persistSession(sessionData);
				}
			});
		}
	}

	private Future<SessionData> createFetchDataFuture(final Persister p, final String sessionId) {
		return executor.submit(new Callable<SessionData>() {
			public SessionData call() throws Exception {
				return p.getSessionData(sessionId);
			}
		});
	}

	private SessionData waitOnSessionData(Future<SessionData> future, Future<SessionData>... others) throws InterruptedException {
		try {
			return future.get(getSecondsToWait(), TimeUnit.SECONDS);
		} catch(InterruptedException i) {
			log.warn("Interrupted while waiting on " + future);
			future.cancel(true);
			for(Future<SessionData> other : others) {
				other.cancel(true);
			}
			throw i;
		} catch(java.util.concurrent.ExecutionException ee) {
			log.warn("Exception while trying to get session data (" + future + ")", ee);
		} catch(java.util.concurrent.TimeoutException te) {
			log.warn("Timeout while trying to get session data (" + future + ")");
			future.cancel(true);
		} catch(java.util.concurrent.CancellationException ce) {
			log.warn("Encountered a cancelled fetch of session data (" + future + ")");
		}
		return null;
	}

	/**
	* Retrieves the session data from the first possible {@link Persister} containing it. May be {@code null}.
	*/
	public SessionData getSessionData(final String sessionId) {
		SessionData session = null;
		
		try {

			final Iterator<Persister> it = persisters.iterator();

			if(!it.hasNext()) return null;

			// Kick off the first couple of fetch attempts
			Future<SessionData> next = createFetchDataFuture(it.next(), sessionId);
			Future<SessionData> nextNext = 
				it.hasNext() ? createFetchDataFuture(it.next(), sessionId) : null;

			do {
				Future<SessionData> current = next;
				next = nextNext;
				nextNext = it.hasNext() ? createFetchDataFuture(it.next(), sessionId) : null;

				session = waitOnSessionData(current, next, nextNext);
			} while(session == null && next != null);

			if(next != null) {
				if(session == null) session = waitOnSessionData(next, nextNext);
				next.cancel(true);
			}

			if(nextNext != null) {
				if(session == null) session = waitOnSessionData(nextNext);
				nextNext.cancel(true);
			}

		} catch(InterruptedException punt) {}

		return session;
	}

	/**
	 * Informs all the {@link Persister} instances to invalidate this session.
	 */
	public void invalidate(final String sessionId) {
		for(final Persister p : persisters) {
			executor.submit(new Runnable() {
				public void run() {
					p.invalidate(sessionId);
				}
			});
		}
	}

}
