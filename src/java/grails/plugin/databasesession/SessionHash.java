package grails.plugin.databasesession;

import java.util.*;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ImmutableMap;
import javax.servlet.http.HttpSession;

/**
* Grabs some data about a session to see if it has changed. This presumes that a change is reflected in 
* either the set of names or the {@link Object#hashCode()} implementation (or its override).
*/
public class SessionHash extends ForwardingMap<String,Integer> {

	private final Map<String,Integer> data;

	private final int maxInactiveInterval;

	public SessionHash(HttpSession session) {
		this.maxInactiveInterval = session.getMaxInactiveInterval();
		final ImmutableMap.Builder<String, Integer> builder = ImmutableMap.builder();
		for(String name : Collections.list(session.getAttributeNames())) {
			Object value = session.getAttribute(name);
			builder.put(name, value == null ? 0 : value.hashCode());
		}
		data = builder.build();
	}

	public Map<String,Integer> delegate() {
		return data;
	}

	public int hashCode() {
		return this.maxInactiveInterval ^ standardHashCode();
	}

	public boolean equals(Object o) {
		if(o instanceof HttpSession) return equals(new SessionHash((HttpSession)o));

		return standardEquals(o) && 
			(o instanceof SessionHash) && 
			this.maxInactiveInterval == ((SessionHash)o).maxInactiveInterval;
	}

}

