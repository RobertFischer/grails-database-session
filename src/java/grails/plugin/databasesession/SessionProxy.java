package grails.plugin.databasesession;

import java.io.Serializable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;


import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionBindingEvent; 
import javax.servlet.http.HttpSessionBindingListener; 
import javax.servlet.http.HttpSessionActivationListener; 

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterators;

import org.apache.log4j.Logger;

/**
 * @author Burt Beckwith
 * @author Robert Fischer
 */
@SuppressWarnings("deprecation")
public class SessionProxy implements HttpSession,Serializable,Cloneable {

	private static final Logger log = Logger.getLogger(SessionProxy.class);

	private final Persister _persister;
	private final String _sessionId;
	private final ServletContext _servletContext;
	private final Map<String,Serializable> _attrs;
	private final long _createdAt;
	private final HttpSessionEvent _event = new HttpSessionEvent(this); // Might as well cache this
	private volatile long _lastAccessedAt;
	private volatile boolean _invalidated;
	private volatile int _maxInactiveInterval;
	

	/**
	 * Constructor. Note that you should call {@link #fireSessionActivationListeners()} after construction is complete.
	 *
	 * @param servletContext the ServletContext
	 * @param persister the persister
	 * @param sessionId session id
	 */
	public SessionProxy(ServletContext servletContext, Persister persister, final String sessionId) {
		this(servletContext, persister, sessionId, persister.getSessionData(sessionId));
	}

	public SessionProxy(final ServletContext servletContext, final Persister persister, final String sessionId, final SessionData data) {
		_servletContext = servletContext;
		_persister = persister;
		_invalidated = false;
		_sessionId = sessionId;

		if(data == null) {
			log.debug("Creating a new session data for " + sessionId);
			_attrs = new ConcurrentHashMap<String,Serializable>();
			_createdAt = System.currentTimeMillis();
			_lastAccessedAt = System.currentTimeMillis();
			_maxInactiveInterval = 600;
		} else {
			log.debug("Using persisted session for " + sessionId + ": " + Arrays.deepToString(data.attrs.keySet().toArray(new String[0])));
			_attrs = new ConcurrentHashMap<String,Serializable>(data.attrs);
			_createdAt = data.createdAt;
			_lastAccessedAt = data.lastAccessedAt;
			_maxInactiveInterval = data.maxInactiveInterval;
		}
		log.debug("Done constructing the proxy session for " + sessionId);
	}

	

	/**
	* Mapping back to the {@link SessionData} holder.
	*/
	public SessionData toData() {
		return SessionData.fromProxy(this);
	}

	public void fireSessionActivationListeners() {
		for(Serializable value : _attrs.values()) {
			if(value instanceof HttpSessionActivationListener) {
				log.debug("Firing sessionActivation for " + value);
				((HttpSessionActivationListener)value).sessionDidActivate(_event);
			}
		}
	}

	public void fireSessionPassivationListeners() {
		for(Serializable value : _attrs.values()) {
			if(value instanceof HttpSessionActivationListener) {
				log.debug("Firing sessionPassivation for " + value);
				((HttpSessionActivationListener)value).sessionWillPassivate(_event);
			}
		}
	}

	public void checkAccess() { 
		if(_invalidated) {
			throw new InvalidatedSessionException("Session " + _sessionId + " is invalid; cannot access/modify it.");
		}
		final long lastAccess = _lastAccessedAt;
		if(lastAccess + (_maxInactiveInterval*1000L) < System.currentTimeMillis()) {
			invalidate();
			throw new InvalidatedSessionException(
				"Session " + _sessionId + " (last accessed at " + new java.sql.Date(lastAccess) + ") is invalid due to age"
			);
		}
		_lastAccessedAt = System.currentTimeMillis();
	}

	@Override
	public Serializable getAttribute(String name) {
		checkAccess();
		return _attrs.get(name);
	}

	@Override @Deprecated
	public Serializable getValue(String name) {
		return getAttribute(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		checkAccess();
		return Collections.enumeration(_attrs.keySet());
	}

	@Override @Deprecated
	public String[] getValueNames() {
		checkAccess();
		return _attrs.keySet().toArray(new String[0]);
	}

	@Override
	public void setAttribute(String name, Object value) {
		checkAccess();
		if(name == null) throw new IllegalArgumentException("Cannot store a null key into the session");
		if(value == null) {
			removeAttribute(name);
		} else {
			final Serializable oldValue;
			try {
				oldValue = _attrs.put(name, (Serializable)value);
			} catch(ClassCastException cce) {
				throw new IllegalStateException("Can only set Serializable values into the session (tried to add: " + value.getClass() + ")");
			}
			if(oldValue != null && oldValue instanceof HttpSessionBindingListener) {
				log.debug("Firing off valueUnbound listener for " + oldValue + " (was attached to '" + name + "')");
				((HttpSessionBindingListener)oldValue).valueUnbound(
					new HttpSessionBindingEvent(this, name, oldValue)
				);
			}
			if(value instanceof HttpSessionBindingListener) {
				log.debug("Firing off valueBound listener for " + value + " (now attached to '" + name + "')");
				((HttpSessionBindingListener)value).valueBound(
					new HttpSessionBindingEvent(this, name, value)
				);
			}
		}
	}

	@Override @Deprecated
	public void putValue(String name, Object value) {
		setAttribute(name, value);
	}

	@Override
	public void removeAttribute(String name) {
		checkAccess();
		Serializable value = _attrs.remove(name);
		if(value != null && value instanceof HttpSessionBindingListener) {
			log.debug("Firing off valueUnbound listener for " + value + " (was attached to '" + name + "')");
			((HttpSessionBindingListener)value).valueUnbound(
				new HttpSessionBindingEvent(this, name, value)
			);
		}
		return;
	}

	@Override @Deprecated
	public void removeValue(String name) {
		removeAttribute(name);
	}

	@Override
	public long getCreationTime() {
		checkAccess();
		return _createdAt;
	}

	@Override
	public String getId() {
		return _sessionId;
	}

	@Override
	public long getLastAccessedTime() {
		checkAccess();
		return _lastAccessedAt;
	}

	@Override
	public ServletContext getServletContext() {
		return _servletContext;
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		_maxInactiveInterval = interval;
	}

	@Override
	public int getMaxInactiveInterval() {
		return _maxInactiveInterval;
	}

	/**
	* Due to security concerns, the returned {@link HttpSessionContext} simply returns {@code null} for all
	* its method calls.
	*/
	@Override @Deprecated
	public HttpSessionContext getSessionContext() {
		return new HttpSessionContext() {
			public HttpSession getSession(String sessionId) {
				return null;
			}
			public Enumeration<String> getIds() {
				return null;
			}
		};
	}

	@Override
	public void invalidate() {
		checkAccess();
		_invalidated = true;
		// A race condition *could* result in a session being invalidated twice, but that's OK
		_persister.invalidate(_sessionId); 
	}

	/**
	* Since we use the built-in session for the first round, this can always be {@code false}.
	*/	
	@Override
	public boolean isNew() {
		checkAccess();
		return false;
	}

	/**
	* Gets an immutable map of all the attributes.
	*/
	public Map<String,Serializable> getAttributes() {
		return ImmutableSortedMap.copyOf(_attrs);
	}

	public long getCreatedAt() {
		return _createdAt;
	}

}
