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

	private volatile List<Persister> persisters = new CopyOnWriteArrayList<Persister>();

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
	@Override
	public void persistSession(final SessionData sessionData) {
		log.debug("Persisting session " + sessionData + " to persister chain");
		for(final Persister p : persisters) {
			p.persistSession(sessionData);
		}
	}

	/**
	* Retrieves the session data from the first possible {@link Persister} containing it. May be {@code null}.
	*/
	@Override
	public SessionData getSessionData(final String sessionId) {
		SessionData session = null;
		
		for(Persister p : persisters) {
			session = p.getSessionData(sessionId);
			if(session != null) break;
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
			log.debug("Submitting invalidation call for session " + sessionId + " to persister " + p);
			p.invalidate(sessionId);
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
