package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.List;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import org.apache.log4j.Logger;

import org.springframework.beans.factory.InitializingBean;

/**
 * Persists data into an in-memory weak hash map. Note that this means that it's easy for session 
 * instances to be lost. But this does make for a fast way to query things right away.
 *
 * @author RobertFischer
 */
public class InMemoryPersister implements Persister, InitializingBean {

	private final Logger log = Logger.getLogger(getClass());

	private volatile Cache<String, SessionData> cache = null;

	private volatile int maximumSize = 100;
	public void setMaximumSize(int maximumSize) {
		this.maximumSize = maximumSize;
	}
	public int getMaximumSize() {
		return maximumSize;
	}

	private volatile int concurrencyLevel = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
	public void setConcurrencyLevel(int concurrencyLevel) {
		this.concurrencyLevel = concurrencyLevel;
	}
	public int getConcurrencyLevel() {
		return concurrencyLevel;
	}

	/**
	* How soon a session expires after its last access
	*/
	private volatile int expiresSeconds = 60;

	/**
	* How soon a session expires after its last access
	*/
	public void setExpiresSeconds(final int expiresSeconds) {
		this.expiresSeconds = expiresSeconds;
	}
	/**
	* How soon a session expires after its last access
	*/
	public int getExpiresSeconds() {
		return expiresSeconds;
	}

	/**
	* Creates the cache based on the configuration
	*/
	@Override
	public void afterPropertiesSet() {
		cache = CacheBuilder.newBuilder() 
			.maximumSize(maximumSize)
			.concurrencyLevel(concurrencyLevel)
			.expireAfterAccess(expiresSeconds, TimeUnit.SECONDS)
			.initialCapacity(2)
			.softValues()
			.removalListener(new RemovalListener<String, SessionData>() {
				public void onRemoval(RemovalNotification<String,SessionData> notification) {
					log.debug("Removing session " + notification.getKey() + " because: " + notification.getCause());
				}
			})
			.build();
	}

	/**
	* Persists a session to the data store. The sessionData may be {@code null}.
	*/
	@Override
	public void persistSession(SessionData sessionData) {
		if(sessionData == null) return;
		log.debug("Persisting session: " + sessionData);
		cache.put(sessionData.sessionId, sessionData);
	}

	/**
	* Retrieves the session data for the given session. May be {@code null}.
	*/
	@Override
	public SessionData getSessionData(String sessionId) {
		SessionData result = cache.getIfPresent(sessionId);
		log.debug("Retrieving session data " + sessionId + ": " + result);
		return result;
	}

	/**
	 * Delete a session and its attributes.
	 * @param sessionId the session id
	 */
	@Override
	public void invalidate(String sessionId) {
		log.debug("Invalidating session " + sessionId);
		cache.invalidate(sessionId);
	}

	/**
	 * Check if the session is valid.
	 * @param sessionId the session id
	 * @return true if the session exists and hasn't been invalidated
	 */
	@Override
	public boolean isValid(String sessionId) {
		return getSessionData(sessionId) != null;
	}

	@Override
	public void cleanUp() {
		cache.cleanUp();
	}

}
