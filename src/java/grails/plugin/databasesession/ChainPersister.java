package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.google.common.util.concurrent.MoreExecutors;

/**
 * A {@link Persister} that attempts a series of persistance strategies in order. 
 *
 * @author Robert Fischer
 */
public class ChainPersister implements Persister {

	private final Logger log = Logger.getLogger(getClass());

	private volatile List<Persister> persisters = new CopyOnWriteArrayList();

	/**
	* The executor that will be used to get some concurrency out of this chain. 
	* If you want to execute things serially (no concurrency), use {@link MoreExecutors#sameThreadExecutor()}.
	*/
	private volatile ExecutorService executor = Executors.newCachedThreadPool();

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
	* Ensures the completion of the future in a seperate task, logging any kind of exception as a consequence.
	*/
	private void consumeFuture(final Future<?> future, final String description) {
		log.debug("Submitting future " + future + " to " + description);
		executor.submit(new Runnable() {
			public void run() {	
				try{
					log.debug("Waiting on " + future + ", which is " + description);
					future.get(getSecondsToWait(), TimeUnit.SECONDS);
					log.debug("Successfully completed " + description);
				} catch(CancellationException ce) {
					log.debug("Discovered cancelled task " + description + ": ignoring");
				} catch(ExecutionException ee) {
					log.error("Error while " + description, ee.getCause());
				} catch(TimeoutException te) {
					log.info("Timeout while " + description);
					consumeFuture(executor.submit(new Callable<Void>() {
						public Void call() throws Exception {
							try {
								future.get(Math.max(1, getSecondsToWait() - 1), TimeUnit.SECONDS);
							} catch(InterruptedException ie) {
								log.warn("Second interruption while " + description + ": cancelling.");
								future.cancel(false);
							} catch(TimeoutException te) {
								log.warn("Second timeout while " + description + ": cancelling.");
								future.cancel(false);
							} 
							return null;
						}
					}), "trying " + description + " a second time.");
				} catch(InterruptedException ie) {
					log.info("Interrupted while " + description);
				} catch(RuntimeException re) {
					log.error("Unknown exception while " + description + ": " + re.getClass().getSimpleName() + " - " + re.getMessage(), re);
				} finally {
					log.debug("Done consuming future for " + description);
				}
			}
		});
		log.debug("Submitted future " + future + " to " + description);
	}

	/**
	* Persists a session to each of the underlying {@link Persister}s. The sessionData may be {@code null}.
	* The persistance is done concurrently using the executor.
	*/
	@Override
	public void persistSession(final SessionData sessionData) {
		log.debug("Persisting session " + sessionData + " to persister chain");
		for(final Persister p : persisters) {
			consumeFuture(executor.submit(new Runnable() {
				public void run() {
					p.persistSession(sessionData);
				}
			}), "persisting session " + sessionData + " to " + p);
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
			log.debug("Waiting on " + future + " to get the session data");
			return future.get(getSecondsToWait(), TimeUnit.SECONDS);
		} catch(InterruptedException i) {
			log.warn("Interrupted while waiting on " + future + ": cancelling it and the subsequent tasks");
			future.cancel(false);
			for(Future<SessionData> other : others) {
				other.cancel(false);
			}
			throw i;
		} catch(CancellationException ce) {
			log.info("Cancelled while attempting to get session data (" + future + ")");
		} catch(java.util.concurrent.ExecutionException ee) {
			log.warn("Exception while trying to get session data (" + future + ")", ee.getCause());
		} catch(java.util.concurrent.TimeoutException te) {
			log.warn("Timeout while trying to get session data (" + future + ")", te);
			future.cancel(false);
		} 
		return null;
	}

	/**
	* Retrieves the session data from the first possible {@link Persister} containing it. May be {@code null}.
	*/
	@Override
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
				next.cancel(false);
			}

			if(nextNext != null) {
				if(session == null) session = waitOnSessionData(nextNext);
				nextNext.cancel(false);
			}

		} catch(InterruptedException punt) {
			log.warn("Interrupted while getting session data: may not return data that is out there");
		}

		if(session != null) {
			log.debug("Found session in chain for session id " + sessionId + ": " + session);
		} else {
			log.debug("No session found in chain for session id " + sessionId);
		}
		return session;
	}

	/**
	 * Informs all the {@link Persister} instances to invalidate this session.
	 */
	@Override
	public void invalidate(final String sessionId) {
		log.debug("Submitting invalidation call to persister chain for session " + sessionId);
		for(final Persister p : persisters) {
			consumeFuture(executor.submit(new Runnable() {
				public void run() {
					log.debug("Submitting invalidation call for session " + sessionId + " to persister " + p);
					p.invalidate(sessionId);
				}
			}), "submitting invalidation call for session " + sessionId + " to persister " + p);
		}
	}

	@Override
	public boolean isValid(final String sessionId) {
		for(Persister p : persisters) {
			boolean isValid = p.isValid(sessionId);
			if(isValid) return isValid;
		}
		return false;
	}

}
