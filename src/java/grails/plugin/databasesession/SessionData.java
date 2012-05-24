package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;

import com.google.common.collect.ImmutableSortedMap;

/**
 * A deeply immutable holder for all the data that we need to pass to/from a session.
 *
 * @author Robert Fischer
 */
public class SessionData {

	public final String sessionId;
	public final SortedMap<String,Serializable> attrs;
	public final long createdAt;
	public final long lastAccessedAt;
	public final int maxInactiveInterval; // In seconds
	public final boolean isNew; 

	public SessionData(
		final String sessionId, final Map<String,Serializable> attrs,
		final long createdAt, final long lastAccessedAt,
		final int maxInactiveInterval, final boolean isNew
	) {
		this.sessionId = sessionId;
		this.attrs = (attrs == null || attrs.isEmpty()) ? Collections.emptySortedMap() : ImmutableSortedMap.copyOf(attrs);
		this.createdAt = createdAt;
		this.lastAccessedAt = lastAccessedAt;
		this.maxInactiveInterval = maxInactiveInterval;
		this.isNew = isNew;
	}

	public String toString() {
		return "SessionData[" + sessionid + "]";
	}

}

