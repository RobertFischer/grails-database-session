package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Burt Beckwith
 * @author Robert Fischer
 */
public interface Persister {

	/**
	* Persists a session to the data store. The map on {@link SessionData#attrs} may be {@code null}, in which case it is treated as 
	* equivalent to an empty map; to delete a session, use {@link #invalidate(String)}.
	*/
	void persistSession(SessionData session);

	/**
	* Retrieves the session data for the given session. Return value will be {@code null} if the session id is not recognized.
	*/
	SessionData getSessionData(String sessionId);

	/**
	 * Effectively delete a session.
   * 
	 * @param sessionId the session id
	 */
	void invalidate(String sessionId);

	/**
	 * Check if the persister is aware of a session with that id and it is not invalidated.
	 * 
	 * @param sessionId the session id
	 * @return true if the session exists and hasn't been invalidated
	 */
	boolean isValid(String sessionId);

	/** 
	* Implements any clean up logic for the persister.
	*/
	void cleanUp();

}
