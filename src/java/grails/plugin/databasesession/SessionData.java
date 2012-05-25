package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;

import javax.servlet.http.HttpSession;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterators;

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

	public static SessionData fromSession(HttpSession session) {
		ImmutableSortedMap.Builder<String,Serializable> builder = ImmutableSortedMap.builder();
		
		for(String name : Collections.list(session.getAttributeNames())) {
			builder.put(name, (Serializable)session.getAttribute(name));
		}
		final ImmutableSortedMap<String,Serializable> attrs = builder.build();
		builder = null;

		return new SessionData(
			session.getId(), attrs,
			session.getCreationTime(), session.getLastAccessedTime(),
			session.getMaxInactiveInterval(), session.isNew()
		);
	}

	public static SessionData fromProxy(SessionProxy proxy) {
		return new SessionData(
			proxy.getId(), ImmutableSortedMap.copyOf(proxy.getAttributes()),
			proxy.getCreationTime(), proxy.getLastAccessedTime(),
			proxy.getMaxInactiveInterval(), proxy.isNew()
		);
	}

	public SessionData(
		final String sessionId, final Map<String,Serializable> attrs,
		final long createdAt, final long lastAccessedAt,
		final int maxInactiveInterval, final boolean isNew
	) {
		this.sessionId = sessionId;
		if(attrs == null || attrs.isEmpty()) {
			this.attrs = ImmutableSortedMap.of();
		} else {
			this.attrs = ImmutableSortedMap.copyOf(attrs);
		}
		this.createdAt = createdAt;
		this.lastAccessedAt = lastAccessedAt;
		this.maxInactiveInterval = maxInactiveInterval;
		this.isNew = isNew;
	}

	public String toString() {
		return "SessionData[" + sessionId + "]";
	}

}

