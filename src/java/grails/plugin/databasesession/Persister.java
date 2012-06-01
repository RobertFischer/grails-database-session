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
	* Retrieves the session data for the given session. Will never be {@code null}, but may be empty. Note that an empty map
	* may reflect an invalid session: check {@link #isValid(String)} for that.
	*/
	SessionData getSessionData(String sessionId);

	/**
	 * Delete a session and its attributes. 
   * 
	 * @param sessionId the session id
	 */
	void invalidate(String sessionId);

	/**
	 * Check if the session is valid.
		* 
	 * @param sessionId the session id
	 * @return true if the session exists and hasn't been invalidated
	 */
	boolean isValid(String sessionId);

	/** 
	* Implements the clean up logic for the persister.
	*/
	void cleanUp();

}
